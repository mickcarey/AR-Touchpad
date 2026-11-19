// Copyright 2026 Paul Gratz
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.pgratz.artouchpad.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.artouchpad.MultiFingerTapTracker
import com.pgratz.artouchpad.FourFingerGesture
import com.pgratz.artouchpad.SettingsEdgeSwipe
import com.pgratz.artouchpad.TouchMode
import com.pgratz.artouchpad.TouchpadViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation


private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF1A2332)
private val SURFACE_DISABLED = Color(0xFF111820)
private val ACCENT = Color(0xFF4FC3F7)
private val ACCENT_DIM = Color(0xFF1A4A6A)
private val TEXT = Color(0xFFE0E0E0)
private val TEXT_DIM = Color(0xFF90A4AE)

private const val MOVE_THRESHOLD = 5f
// Combined travel (px) two fingers must cover before the gesture is latched as scroll or pinch.
private const val TWO_FINGER_SLOP = 12f
private const val TAP_MAX_MS = 220L
private const val LONG_PRESS_MS = 600L

// Fullscreen touchpad with an invisible bottom strip reserved for Android navigation.
@Composable
fun TouchpadScreen(viewModel: TouchpadViewModel, settingsOnPhone: Boolean = false) {
    val state by viewModel.state.collectAsState()
    val haptic = LocalHapticFeedback.current
    val feedbackScope = rememberCoroutineScope()
    if (state.calibratingOrientation) {
        OrientationCalibrationScreen(onCancel = { viewModel.finishOrientationCalibration() },
            onSave = viewModel::finishOrientationCalibration)
    } else if (state.showSettings && settingsOnPhone) {
        Box(Modifier.fillMaxSize().background(BG).systemBarsPadding()) { TouchpadSettings(viewModel) }
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)
            .windowInsetsPadding(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))) {
            TouchpadSurface(Modifier.fillMaxSize(), state.mouseReady,
                viewModel::moveCursor, viewModel::performClick,
                viewModel::performRightClick, viewModel::toggleMoonlightCapture,
                viewModel::performScroll, viewModel::pinchZoom, viewModel::setTouchMode,
                viewModel::startSelectDrag, viewModel::endSelectDrag, viewModel::cancelSelectDrag,
                viewModel::setDragReady,
                viewModel::openSettings,
                pocketMode = state.pocketInputLocked,
                onTogglePocketMode = {
                    val locked = viewModel.togglePocketMode()
                    // Framework touch feedback respects the system haptics setting.
                    // This scope survives the lock change and the fingers lifting.
                    feedbackScope.launch {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!locked) {
                            delay(120)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
                showVisuals = !(state.blackTouchpad && state.mouseReady && state.externalDisplayConnected))
            if (!state.mouseReady) Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (!state.externalDisplayConnected) "Connect an external display"
                    else if (viewModel.mouse.isConnected) "Connecting mouse…"
                    else "Touchpad disconnected", color = TEXT, fontSize = 18.sp)
                ConnectionControls(viewModel)
                TextButton(onClick = viewModel::openSettings) { Text("Settings", color = ACCENT) }
            }
        }
    }
}

@Composable
fun TouchpadSettings(viewModel: TouchpadViewModel) {
    val state by viewModel.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        SettingsPanel(
            sensitivity = state.sensitivity, scrollSpeed = state.scrollSpeed,
            naturalScroll = state.naturalScroll, axisRotation = state.axisRotation,
            calibratedRotationDegrees = state.calibratedRotationDegrees,
            calibratingOrientation = state.calibratingOrientation,
            onCalibrate = viewModel::startOrientationCalibration,
            dimDelaySec = state.dimDelaySec, dexKeyboard = state.dexKeyboardEnabled,
            blackTouchpad = state.blackTouchpad, onBlackTouchpad = viewModel::setBlackTouchpad,
            pocketMode = state.pocketMode, onPocketMode = viewModel::setPocketMode,
            dexKeyboardError = state.dexKeyboardError, allDisplays = state.allDisplays,
            targetDisplay = state.targetDisplay, onSensitivity = viewModel::setSensitivity,
            onScrollSpeed = viewModel::setScrollSpeed, onNaturalScroll = viewModel::setNaturalScroll,
            onAxisRotation = viewModel::setAxisRotation, onDimDelay = viewModel::setDimDelay,
            onDexKeyboard = viewModel::setDexKeyboard, onDismiss = viewModel::closeSettings,
            connectionControls = { ConnectionControls(viewModel) },
        )
    }
}

@Composable
private fun ConnectionControls(viewModel: TouchpadViewModel) {
    val state by viewModel.state.collectAsState()
    when {
        !state.shizukuAvailable -> Text("Start Shizuku to connect the mouse.", color = TEXT_DIM)
        !state.shizukuPermission -> TextButton(onClick = viewModel::requestShizukuPermission) {
            Text("Grant Shizuku", color = ACCENT)
        }
        !state.mouseReady && !viewModel.mouse.isConnected -> TextButton(onClick = { viewModel.mouse.bind() }) { Text("Connect mouse", color = ACCENT) }
    }
}

// The main touch surface. Intercepts raw pointer events with a single pointerInput handler:
//   1 finger: tap (click), double-tap, long-press (right-click), long-press+drag (select text),
//             or drag (cursor move).
//   2 fingers: tap (right-click), pinch (spread > translate → zoom) or drag (translate > spread → scroll),
//             decided once per gesture after TWO_FINGER_SLOP of travel.
//   3 fingers: tap to toggle Moonlight input capture.
// Renders a dot grid and live touch point indicators on a Canvas.
private enum class TwoFingerMode { UNDECIDED, SCROLL, PINCH }

@Composable
private fun TouchpadSurface(
    modifier: Modifier,
    enabled: Boolean,
    onMoveCursor: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onRightClick: () -> Unit,
    onThreeFingerTap: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onTouchModeChanged: (TouchMode) -> Unit,
    onSelectStart: () -> Unit,
    onSelectEnd: () -> Unit,
    onSelectCancel: () -> Unit,
    onDragReady: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    pocketMode: Boolean,
    onTogglePocketMode: () -> Unit,
    showVisuals: Boolean,
) {
    var touchPoints by remember { mutableStateOf(listOf<Offset>()) }
    val haptic = LocalHapticFeedback.current
    val isPocketMode by rememberUpdatedState(pocketMode)
    val togglePocketMode by rememberUpdatedState(onTogglePocketMode)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(if (enabled) SURFACE else SURFACE_DISABLED)
                .pointerInput(enabled) {

                    var lastPositions = mapOf<PointerId, Offset>()
                    var downPosition = Offset.Zero
                    var downTime = 0L
                    var didMove = false
                    var isLongPress = false
                    var isSelectMode = false
                    var longPressJob: Job? = null
                    var fourFingerJob: Job? = null
                    var scheduledHold: Long? = null
                    var twoFingerMode = TwoFingerMode.UNDECIDED
                    var pendingDx = 0f
                    var pendingDy = 0f
                    var pendingSpan = 0f
                    val multiTap = MultiFingerTapTracker(TWO_FINGER_SLOP, TAP_MAX_MS)
                    val fourFinger = FourFingerGesture(slop = 16.dp.toPx())
                    val edgeSwipe = SettingsEdgeSwipe(56.dp.toPx(), 72.dp.toPx(), 36.dp.toPx())

                    try {
                        coroutineScope {
                            val scope = this  // CoroutineScope for launching the long-press timer

                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val now = event.changes.first().uptimeMillis
                                    val pressed = event.changes.filter { it.pressed }
                                    val justPressed = event.changes.filter { it.pressed && !it.previousPressed }
                                    val justReleased = event.changes.filter { !it.pressed && it.previousPressed }
                                    val contacts = event.changes.map {
                                        MultiFingerTapTracker.Contact(it.id.value, it.position.x, it.position.y, it.pressed)
                                    }
                                    // Track releases even when another recognizer consumes the stream.
                                    val tapFingers = multiTap.update(now, contacts)

                                    // These controls work even while Pocket mode blocks mouse input.
                                    val fourDecision = fourFinger.update(now, contacts)
                                    val hold = fourFinger.holdDeadline
                                    if (hold != scheduledHold) {
                                        fourFingerJob?.cancel()
                                        scheduledHold = hold
                                        fourFingerJob = hold?.let { deadline -> scope.launch {
                                            delay((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                                            if (fourFinger.onTime(SystemClock.uptimeMillis()) == FourFingerGesture.Decision.TOGGLE_POCKET) {
                                                togglePocketMode()
                                            }
                                        } }
                                    }
                                    if (fourDecision != FourFingerGesture.Decision.PASS) {
                                        longPressJob?.cancel()
                                        multiTap.cancelTap()
                                        if (isSelectMode) { onSelectCancel(); isSelectMode = false }
                                        isLongPress = false
                                        didMove = true
                                        touchPoints = emptyList()
                                        lastPositions = emptyMap()
                                        onTouchModeChanged(TouchMode.IDLE)
                                        event.changes.forEach { it.consume() }
                                        when (fourDecision) {
                                            FourFingerGesture.Decision.OPEN_SETTINGS -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onOpenSettings()
                                            }
                                            FourFingerGesture.Decision.TOGGLE_POCKET -> togglePocketMode()
                                            else -> Unit
                                        }
                                        continue
                                    }

                                    val firstDown = justPressed.isNotEmpty() && event.changes.none { it.previousPressed }
                                    val previousEdgeDecision = edgeSwipe.decision
                                    if (firstDown) {
                                        val p = justPressed.first().position
                                        edgeSwipe.begin(p.x, p.y, size.width.toFloat(), size.height.toFloat(), now)
                                    }
                                    val firstPoint = event.changes.first().position
                                    val edgeDecision = edgeSwipe.update(firstPoint.x, firstPoint.y, now,
                                        max(pressed.size, event.changes.count { it.previousPressed }), pressed.isEmpty())
                                    if (edgeDecision == SettingsEdgeSwipe.Decision.CONSUMED ||
                                        edgeDecision == SettingsEdgeSwipe.Decision.OPEN_SETTINGS) {
                                        longPressJob?.cancel()
                                        fourFinger.cancel()
                                        multiTap.cancelTap()
                                        didMove = true
                                        touchPoints = emptyList()
                                        lastPositions = emptyMap()
                                        onTouchModeChanged(TouchMode.IDLE)
                                        event.changes.forEach { it.consume() }
                                        if (edgeDecision == SettingsEdgeSwipe.Decision.OPEN_SETTINGS) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onOpenSettings()
                                        }
                                        continue
                                    }
                                    if (!enabled || isPocketMode) {
                                        longPressJob?.cancel()
                                        if (isSelectMode) { onSelectCancel(); isSelectMode = false }
                                        touchPoints = emptyList()
                                        lastPositions = emptyMap()
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }

                                    // Rejected edge candidates return their buffered travel to the mouse.
                                    // Taps and stationary long-presses keep their existing timing.
                                    if (!firstDown && previousEdgeDecision == SettingsEdgeSwipe.Decision.PENDING &&
                                        edgeDecision == SettingsEdgeSwipe.Decision.TRACKPAD && event.changes.size == 1) {
                                        if (pressed.isNotEmpty()) lastPositions = mapOf(pressed.first().id to downPosition)
                                        else {
                                            val delta = firstPoint - downPosition
                                            if (abs(delta.x) > MOVE_THRESHOLD || abs(delta.y) > MOVE_THRESHOLD) {
                                                didMove = true
                                                onMoveCursor(delta.x, delta.y)
                                            }
                                        }
                                    }

                                    touchPoints = pressed.map { it.position }

                                    if (justPressed.isNotEmpty() && event.changes.none { it.previousPressed }) {
                                        longPressJob?.cancel()
                                        isLongPress = false
                                        isSelectMode = false
                                        downTime = now
                                        didMove = false
                                        twoFingerMode = TwoFingerMode.UNDECIDED
                                        pendingDx = 0f; pendingDy = 0f; pendingSpan = 0f
                                        downPosition = pressed.first().position
                                        lastPositions = pressed.associate { it.id to it.position }
                                        // Timer fires after LONG_PRESS_MS if finger hasn't moved;
                                        // Confirm readiness visually as well as with haptics.
                                        // Subsequent movement, not the cue itself, presses BTN_LEFT.
                                        longPressJob = if (pressed.size == 1) scope.launch {
                                            delay(LONG_PRESS_MS)
                                            if (isPocketMode) return@launch
                                            isLongPress = true
                                            try {
                                                onDragReady(true)
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                awaitCancellation()
                                            } finally { onDragReady(false) }
                                        } else null
                                    }

                                    if (multiTap.maxFingers >= 2) {
                                        longPressJob?.cancel()
                                        isLongPress = false
                                        if (isSelectMode) { onSelectEnd(); isSelectMode = false }
                                    }

                                    // Do not turn trailing fingers into cursor movement/scroll, or
                                    // a three-finger gesture into a two-finger action during release.
                                    if (edgeDecision == SettingsEdgeSwipe.Decision.PENDING &&
                                        (abs(firstPoint.x - downPosition.x) > MOVE_THRESHOLD ||
                                            abs(firstPoint.y - downPosition.y) > MOVE_THRESHOLD)) {
                                        didMove = true
                                        longPressJob?.cancel()
                                    }
                                    val suppressMotion = edgeDecision == SettingsEdgeSwipe.Decision.PENDING || multiTap.maxFingers >= 3 ||
                                        (multiTap.maxFingers >= 2 && multiTap.releasing)
                                    if (suppressMotion) {
                                        lastPositions = pressed.associate { it.id to it.position }
                                        event.changes.forEach { it.consume() }
                                    } else when (pressed.size) {
                                        1 -> {
                                            val p = pressed.first()
                                            val last = lastPositions[p.id]
                                            if (last != null) {
                                                val dx = p.position.x - last.x
                                                val dy = p.position.y - last.y
                                                // Latch the drag on cumulative displacement from the touch-down
                                                // point, never on the per-event delta: at 120 Hz a deliberate slow
                                                // drag delivers barely 1 px per event, so a per-event test never
                                                // crosses the threshold and the cursor stays frozen no matter how
                                                // far the finger actually travels.
                                                val totalDx = p.position.x - downPosition.x
                                                val totalDy = p.position.y - downPosition.y
                                                val moved = abs(totalDx) > MOVE_THRESHOLD || abs(totalDy) > MOVE_THRESHOLD

                                                // After long press fired: first movement enters select mode
                                                // (BTN_LEFT pressed; subsequent moves extend text selection).
                                                if (isLongPress && !isSelectMode && moved) {
                                                    fourFinger.cancel()
                                                    isSelectMode = true
                                                    didMove = true
                                                    onSelectStart()
                                                    onTouchModeChanged(TouchMode.SELECT)
                                                }
                                                // Before long press fires: movement cancels the timer → normal drag.
                                                // Threshold only gates the tap→drag transition; once dragging,
                                                // every delta is forwarded so slow movements aren't swallowed.
                                                if (!didMove && moved && !isLongPress) {
                                                    didMove = true
                                                    multiTap.cancelTap()
                                                    longPressJob?.cancel()
                                                }

                                                if (isSelectMode || didMove) {
                                                    onMoveCursor(dx, dy)
                                                    if (!isSelectMode) onTouchModeChanged(TouchMode.CURSOR)
                                                }
                                            }
                                            lastPositions = pressed.associate { it.id to it.position }
                                            p.consume()
                                        }
                                        2 -> {
                                            // Second finger cancels any in-progress select drag.
                                            if (isSelectMode) { onSelectEnd(); isSelectMode = false }
                                            longPressJob?.cancel()

                                            val newPositions = pressed.associate { it.id to it.position }
                                            if (lastPositions.size != 2) {
                                                // Second finger just landed: a new two-finger gesture.
                                                twoFingerMode = TwoFingerMode.UNDECIDED
                                                pendingDx = 0f; pendingDy = 0f; pendingSpan = 0f
                                            }
                                            if (lastPositions.size == 2) {
                                                val ids = pressed.map { it.id }
                                                val p0prev = lastPositions[ids[0]]
                                                val p1prev = lastPositions[ids[1]]
                                                val p0curr = newPositions[ids[0]]
                                                val p1curr = newPositions[ids[1]]
                                                if (p0prev != null && p1prev != null && p0curr != null && p1curr != null) {
                                                    val dx = ((p0curr.x - p0prev.x) + (p1curr.x - p1prev.x)) / 2f
                                                    val dy = ((p0curr.y - p0prev.y) + (p1curr.y - p1prev.y)) / 2f
                                                    val pdx = p1prev.x - p0prev.x; val pdy = p1prev.y - p0prev.y
                                                    val cdx = p1curr.x - p0curr.x; val cdy = p1curr.y - p0curr.y
                                                    val prevSpan = sqrt(pdx * pdx + pdy * pdy)
                                                    val currSpan = sqrt(cdx * cdx + cdy * cdy)
                                                    val dSpan = currSpan - prevSpan
                                                    when (twoFingerMode) {
                                                        TwoFingerMode.UNDECIDED -> {
                                                            // Decide once, on travel accumulated since the second
                                                            // finger landed, then stay decided until a finger lifts.
                                                            // Judging each frame on its own is wrong: at 120 Hz a slow
                                                            // scroll moves under 1 px per frame, less than the jitter
                                                            // in the gap between two fingertips, so a third of the
                                                            // frames would be mistaken for a pinch and dropped.
                                                            pendingDx += dx; pendingDy += dy; pendingSpan += dSpan
                                                            val travel = sqrt(pendingDx * pendingDx + pendingDy * pendingDy)
                                                            if (max(travel, abs(pendingSpan)) >= TWO_FINGER_SLOP) {
                                                                fourFinger.cancel()
                                                                multiTap.cancelTap()
                                                                // The travel that got us here is forwarded, not discarded.
                                                                if (abs(pendingSpan) > travel) {
                                                                    twoFingerMode = TwoFingerMode.PINCH
                                                                    onPinch(pendingSpan)
                                                                } else {
                                                                    twoFingerMode = TwoFingerMode.SCROLL
                                                                    onScroll(pendingDx, pendingDy)
                                                                    onTouchModeChanged(TouchMode.SCROLL)
                                                                }
                                                            }
                                                        }
                                                        TwoFingerMode.SCROLL -> if (dx != 0f || dy != 0f) {
                                                            onScroll(dx, dy)
                                                            onTouchModeChanged(TouchMode.SCROLL)
                                                        }
                                                        TwoFingerMode.PINCH -> if (dSpan != 0f) onPinch(dSpan)
                                                    }
                                                }
                                            }
                                            lastPositions = newPositions
                                            pressed.forEach { it.consume() }
                                        }
                                    }

                                    if (justReleased.isNotEmpty() && pressed.isEmpty()) {
                                        val duration = now - downTime
                                        longPressJob?.cancel()
                                        onTouchModeChanged(TouchMode.IDLE)

                                        when {
                                            tapFingers == 2 -> onRightClick()
                                            tapFingers == 3 -> onThreeFingerTap()
                                            multiTap.maxFingers >= 2 -> Unit
                                            isSelectMode -> onSelectEnd()
                                            !didMove && duration >= LONG_PRESS_MS -> onRightClick()
                                            !didMove && duration < TAP_MAX_MS -> {
                                                // One physical tap is one click. Android/apps detect
                                                // double-clicks from the two real clicks and their timing.
                                                onClick()
                                            }
                                        }
                                        isLongPress = false
                                        isSelectMode = false
                                        lastPositions = emptyMap()
                                        touchPoints = emptyList()
                                    }
                                }
                            }
                        }
                    } finally {
                        // Overview/Home can cancel pointer input without an ordinary release.
                        longPressJob?.cancel()
                        fourFingerJob?.cancel()
                        fourFinger.cancel()
                        if (isSelectMode) onSelectCancel()
                        onTouchModeChanged(TouchMode.IDLE)
                        touchPoints = emptyList()
                    }
                },
        ) {
            // The opaque black overlay hides these drawings; avoid repainting them per sample.
            if (!showVisuals) return@Canvas
            // Dot grid
            val spacing = 32.dp.toPx()
            val cols = (size.width / spacing).toInt() + 1
            val rows = (size.height / spacing).toInt() + 1
            val xOffset = (size.width - (cols - 1) * spacing) / 2f
            val yOffset = (size.height - (rows - 1) * spacing) / 2f
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    drawCircle(
                        color = Color(0xFF263545),
                        radius = 1.8.dp.toPx(),
                        center = Offset(xOffset + col * spacing, yOffset + row * spacing),
                    )
                }
            }

            if (!enabled || pocketMode) return@Canvas

            // Live touch points
            touchPoints.forEach { pt ->
                drawCircle(color = ACCENT.copy(alpha = 0.2f), radius = 28.dp.toPx(), center = pt)
                drawCircle(color = ACCENT, radius = 6.dp.toPx(), center = pt)
                drawCircle(
                    color = ACCENT.copy(alpha = 0.5f),
                    radius = 18.dp.toPx(),
                    center = pt,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

    }
}
