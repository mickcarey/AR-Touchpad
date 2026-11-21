// Copyright 2026 Paul Gratz
// Modifications Copyright 2026 Michael Carey
// Modified for the Michael Carey fork, including the package namespace migration.
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

package au.com.michaelcarey.artouchpad.ui

import android.os.SystemClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.michaelcarey.artouchpad.MultiFingerTapTracker
import au.com.michaelcarey.artouchpad.TouchGestureEngine
import au.com.michaelcarey.artouchpad.TouchMode
import au.com.michaelcarey.artouchpad.TouchpadViewModel
import au.com.michaelcarey.artouchpad.TouchpadBuild
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch



// Fullscreen touchpad with an invisible bottom strip reserved for Android navigation.
@Composable
internal fun TouchpadScreen(viewModel: TouchpadViewModel, settingsOnPhone: Boolean,
    onSwitchBuild: (TouchpadBuild) -> Unit, onRoomMeter: (Boolean) -> Unit, onShowPhoneOverview: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val feedbackScope = rememberCoroutineScope()
    if (state.calibratingOrientation) {
        OrientationCalibrationScreen(onCancel = { viewModel.finishOrientationCalibration() },
            onSave = viewModel::finishOrientationCalibration)
    } else if (state.showSettings && settingsOnPhone) {
        Box(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) { Launchpad(viewModel, onSwitchBuild, onRoomMeter) }
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)
            // Cover the bottom strip too so launchers can suppress horizontal quick-switch.
            .then(if (state.mouseReady) Modifier.systemGestureExclusion() else Modifier)) {
            TouchpadSurface(Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom)), state.mouseReady,
                viewModel::moveCursor, viewModel::performClick,
                viewModel::performRightClick, viewModel::performThreeFingerAction,
                viewModel::performScroll, viewModel::pinchZoom, viewModel::setTouchMode,
                viewModel::startSelectDrag, viewModel::endSelectDrag, viewModel::cancelSelectDrag,
                viewModel::setDragReady,
                onDragHeld = viewModel::setDragHeld, holdMs = state.holdMs, dragLock = state.dragLock,
                inputEpoch = state.inputEpoch,
                viewModel::toggleSettings,
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
            if (state.mouseReady) {
                Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.mandatorySystemGestures)
                    .pointerInput(onShowPhoneOverview) {
                        val minTravel = 48.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            do {
                                val finger = awaitPointerEvent().changes.singleOrNull() ?: return@awaitEachGesture
                                if (finger.isConsumed) return@awaitEachGesture
                                if (finger.changedToUp()) {
                                    val travel = finger.position - down.position
                                    if (-travel.y >= minTravel && -travel.y > kotlin.math.abs(travel.x)) {
                                        onShowPhoneOverview()
                                    }
                                }
                            } while (finger.pressed)
                        }
                    })
            }
            if (state.dragHeld && !state.blackTouchpad) Text("Drag locked · tap to release",
                Modifier.align(Alignment.TopCenter).padding(24.dp), color = colors.onSurface)
            if (!state.mouseReady) Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Touchpad unavailable", color = colors.onSurface, fontSize = 18.sp)
                if (!state.externalDisplayConnected) Text("Connect an external display.", color = colors.onSurfaceVariant)
                ConnectionControls(viewModel)
                TextButton(onClick = viewModel::openSettings) { Text("Launchpad", color = colors.primary) }
            }
        }
    }
}

@Composable
internal fun Launchpad(viewModel: TouchpadViewModel, onSwitchBuild: (TouchpadBuild) -> Unit,
    onRoomMeter: (Boolean) -> Unit, nonFocusable: Boolean = false) {
    val state by viewModel.state.collectAsState()
    // Observe other players/hardware buttons only while settings is composed. Closing or
    // backgrounding settings cancels this work; no permanent polling of media sessions.
    LaunchedEffect(state.mouseReady) {
        if (state.mouseReady) while (true) {
            viewModel.refreshMediaState()
            delay(500)
        }
    }
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
            appControls = { ConnectionControls(viewModel) },
            availableBuild = installedAlternativeBuild(),
            onSwitchBuild = onSwitchBuild,
            onSpeedFinished = viewModel::persistInputSettings,
            extraControls = { ExtraSettings(viewModel, state) },
            mediaEnabled = state.touchpadInputEnabled,
            onMediaKey = viewModel::sendMediaControl,
            mediaState = state.media,
            nonFocusable = nonFocusable,
            showTitle = state.launchpad.showTitle,
            indicators = {
                if (state.pocketMode) Text("🔒  Pocket mode", style = MaterialTheme.typography.labelLarge)
                if (state.launchpad.roomMeter) {
                    val level by viewModel.roomLevel.collectAsState()
                    val reference by viewModel.quietReference.collectAsState()
                    Text(level.label(reference),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
            },
            apps = { library, onAdd ->
                PinnedApps(state.launchpad.pinned, state.targetDisplay?.id, library, onAdd,
                    { pins -> viewModel.updateLaunchpad { it.copy(pinned = pins) } }, viewModel::closeSettings)
            },
            clock = { LaunchpadClock(state.launchpad.worldZones) },
            recovery = { RecoverySettings(viewModel, state) },
            appearance = {
                LaunchpadSettings(state.launchpad, viewModel::updateLaunchpad, onRoomMeter) {
                    val level by viewModel.roomLevel.collectAsState()
                    val reference by viewModel.quietReference.collectAsState()
                    OutlinedButton(onClick = { viewModel.quietReference.value = level.recentAverage },
                        enabled = level.recentAverage != null) {
                        Text(if (reference == null) "Set quiet reference" else "Reset quiet reference · $reference dBFS")
                    }
                }
            },
        )
    }
}

@Composable
private fun installedAlternativeBuild(): TouchpadBuild? {
    val other = TouchpadBuild.alternative ?: return null
    val context = LocalContext.current
    var available by remember(context) { mutableStateOf(false) }
    DisposableEffect(context) {
        fun refresh() { available = other.launchIntent(context) != null }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
            addDataSchemeSpecificPart(other.packageId, PatternMatcher.PATTERN_LITERAL)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return other.takeIf { available }
}

@Composable
private fun ConnectionControls(viewModel: TouchpadViewModel) {
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    when {
        !state.shizukuAvailable -> Text("Start Shizuku to connect the mouse.", color = colors.onSurfaceVariant)
        !state.shizukuPermission -> TextButton(onClick = viewModel::requestShizukuPermission) {
            Text("Grant Shizuku", color = colors.primary)
        }
        !state.mouseReady -> Column {
            Text(state.connectionMessage, color = colors.onSurfaceVariant)
            TextButton(onClick = viewModel::retryConnection) { Text("Retry connection", color = colors.primary) }
        }
    }
}

// The main touch surface. Intercepts raw pointer events with a single pointerInput handler:
//   1 finger: tap (click), double-tap, long-press (right-click), long-press+drag (select text),
//             or drag (cursor move).
//   2 fingers: tap (right-click), pinch (spread > translate → zoom) or drag (translate > spread → scroll),
//             decided once per gesture after a small amount of travel.
//   3 fingers: user's chosen shortcut. Four fingers: settings or Pocket mode.
// Renders a dot grid and live touch point indicators on a Canvas.

@Composable
private fun TouchpadSurface(
    modifier: Modifier,
    enabled: Boolean,
    onMoveCursor: (Float, Float, Long) -> Unit,
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
    onDragHeld: (Boolean) -> Unit,
    holdMs: Long,
    dragLock: Boolean,
    inputEpoch: Long,
    onToggleSettings: () -> Unit,
    pocketMode: Boolean,
    onTogglePocketMode: () -> Unit,
    showVisuals: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    var touchPoints by remember { mutableStateOf(listOf<Offset>()) }
    val haptic = LocalHapticFeedback.current
    val isPocketMode by rememberUpdatedState(pocketMode)
    val togglePocketMode by rememberUpdatedState(onTogglePocketMode)
    val visuals by rememberUpdatedState(showVisuals)
    val density = LocalDensity.current.density
    var currentEngine by remember { mutableStateOf<TouchGestureEngine?>(null) }
    LaunchedEffect(inputEpoch) { currentEngine?.invalidate() }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(if (enabled) colors.surfaceContainer else colors.surface)
                .pointerInput(enabled, holdMs, dragLock, density) {
                    val engine = TouchGestureEngine(object : TouchGestureEngine.Output {
                        override fun move(dx: Float, dy: Float, time: Long) = onMoveCursor(dx, dy, time)
                        override fun click() = onClick()
                        override fun rightClick() = onRightClick()
                        override fun threeFingerTap() = onThreeFingerTap()
                        override fun scroll(dx: Float, dy: Float) = onScroll(dx, dy)
                        override fun pinch(delta: Float) = onPinch(delta)
                        override fun mode(mode: TouchMode) = onTouchModeChanged(mode)
                        override fun startDrag() = onSelectStart()
                        override fun endDrag() = onSelectEnd()
                        override fun cancelDrag() = onSelectCancel()
                        override fun dragHeld(held: Boolean) = onDragHeld(held)
                        override fun dragReady(ready: Boolean) {
                            onDragReady(ready)
                            if (ready) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        override fun settings() {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSettings()
                        }
                        override fun pocket() = togglePocketMode()
                    }, holdMs, dragLock, density)
                    currentEngine = engine
                    var timer: Job? = null
                    try {
                        coroutineScope {
                            val scope = this
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    timer?.cancel()
                                    // Compose reports ACTION_CANCEL as already-consumed releases.
                                    if (event.changes.none { it.pressed } &&
                                        event.changes.any { it.previousPressed && it.isConsumed }) {
                                        engine.cancel()
                                        touchPoints = emptyList()
                                        continue
                                    }
                                    engine.setInputAllowed(enabled && !isPocketMode)
                                    val contacts = event.changes.map {
                                        MultiFingerTapTracker.Contact(it.id.value, it.position.x, it.position.y, it.pressed)
                                    }
                                    engine.event(event.changes.first().uptimeMillis, contacts,
                                        size.width.toFloat(), size.height.toFloat())
                                    engine.nextDeadline?.let { deadline ->
                                        timer = scope.launch {
                                            delay((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                                            engine.setInputAllowed(enabled && !isPocketMode)
                                            engine.onTime(SystemClock.uptimeMillis())
                                        }
                                    }
                                    if (visuals) touchPoints = event.changes.filter { it.pressed }.map { it.position }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    } finally {
                        timer?.cancel()
                        engine.cancel()
                        if (currentEngine === engine) currentEngine = null
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
                        color = colors.outlineVariant,
                        radius = 1.8.dp.toPx(),
                        center = Offset(xOffset + col * spacing, yOffset + row * spacing),
                    )
                }
            }

            if (!enabled || pocketMode) return@Canvas

            // Live touch points
            touchPoints.forEach { pt ->
                drawCircle(color = colors.primary.copy(alpha = 0.2f), radius = 28.dp.toPx(), center = pt)
                drawCircle(color = colors.primary, radius = 6.dp.toPx(), center = pt)
                drawCircle(
                    color = colors.primary.copy(alpha = 0.5f),
                    radius = 18.dp.toPx(),
                    center = pt,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

    }
}
