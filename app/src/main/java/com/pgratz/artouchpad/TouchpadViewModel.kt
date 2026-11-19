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

package com.pgratz.artouchpad

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TouchMode { IDLE, CURSOR, SCROLL, SELECT }

// WindowManager DISPLAY_IME_POLICY_* values, passed to IWindowManager.setDisplayImePolicy.
private const val IME_POLICY_LOCAL = 0     // IME on the display that owns the focused field
private const val IME_POLICY_FALLBACK = 1  // IME on the default display (phone) — DeX-style

// Idle-dim choices offered in the settings panel, in seconds; 0 means "never dim".
val DIM_DELAY_CHOICES_SEC = listOf(0, 15, 30, 60, 120, 300)
const val DEFAULT_DIM_DELAY_SEC = 30

data class DisplayInfo(val id: Int, val name: String, val width: Int, val height: Int)

data class TouchpadState(
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val mouseReady: Boolean = false,
    val allDisplays: List<DisplayInfo> = emptyList(),
    val targetDisplay: DisplayInfo? = null,
    val sensitivity: Float = 0.5f,
    val scrollSpeed: Float = 0.8f,
    val naturalScroll: Boolean = true,
    val axisRotation: AxisRotation = AxisRotation.NONE,
    val calibratedRotationDegrees: Float? = null,
    val calibratingOrientation: Boolean = false,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
    // Seconds of no input before MainActivity scrims the touchpad UI. 0 = never dim.
    val dimDelaySec: Int = DEFAULT_DIM_DELAY_SEC,
    val blackTouchpad: Boolean = false,
    val pocketMode: Boolean = false,
    // User preference: show the keyboard on the phone (IME fallback policy) instead of
    // on the glasses. dexKeyboardActive reflects whether the policy actually took effect
    // (false when `wm set-display-ime-policy` is unsupported on this build).
    val dexKeyboardEnabled: Boolean = false,
    val dexKeyboardActive: Boolean = false,
    val dexKeyboardError: Boolean = false,
) {
    // Settings temporarily borrows the pointer without changing the saved Pocket mode.
    val pocketInputLocked get() = pocketMode && !showSettings
    val touchpadInputEnabled get() = mouseReady && !pocketInputLocked && !calibratingOrientation
    private val calibratedRotation = calibratedRotationDegrees?.let(::CalibratedRotation)
    fun rotateInput(x: Float, y: Float) = calibratedRotation?.rotate(x, y) ?: axisRotation.rotate(x, y)
    val settingsRequirePhone get() = !mouseReady || targetDisplay == null
    val externalDisplayConnected get() = targetDisplay != null
    val displayWidth get() = targetDisplay?.width ?: 1920
    val displayHeight get() = targetDisplay?.height ?: 1080
}

class TouchpadViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("touchpad_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TouchpadState(
        sensitivity = prefs.getFloat("sensitivity", 0.5f),
        scrollSpeed = prefs.getFloat("scroll_speed", 0.8f),
        naturalScroll = prefs.getBoolean("natural_scroll", true),
        axisRotation = AxisRotation.fromDegrees(prefs.getInt("axis_rotation_degrees", 0)),
        calibratedRotationDegrees = if (prefs.contains("calibrated_rotation_degrees"))
            prefs.getFloat("calibrated_rotation_degrees", 0f).takeIf { it.isFinite() && it in -180f..180f } else null,
        dimDelaySec = prefs.getInt("dim_delay_sec", DEFAULT_DIM_DELAY_SEC),
        blackTouchpad = prefs.getBoolean("black_touchpad", false),
        pocketMode = prefs.getBoolean("pocket_mode", false),
        dexKeyboardEnabled = prefs.getBoolean("dex_keyboard", false),
    ))
    val state: StateFlow<TouchpadState> = _state.asStateFlow()
    // Transient feedback only: restoring a saved mode must not replay a notification.
    private val _pocketModeChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val pocketModeChanges = _pocketModeChanges.asSharedFlow()
    private val _dragReadyChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val dragReadyChanges = _dragReadyChanges.asSharedFlow()
    fun setDragReady(ready: Boolean) {
        if (!ready || _state.value.touchpadInputEnabled) _dragReadyChanges.tryEmit(ready)
    }

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val mouse = ShizukuMouseController()

    private companion object {
        const val PINCH_PX_PER_NOTCH = 200f
        const val PINCH_STEP_PX = 20f
    }

    private var pinchAccum = 0f
    private var smoothDx = 0f
    private var smoothDy = 0f
    private var appliedImePolicy: Pair<Int, Int>? = null
    private var pendingImePolicy: Pair<Int, Int>? = null
    private var imePolicyJob: Job? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        mouse.init(::refresh)
        displayManager.registerDisplayListener(displayListener, null)
        refresh()
        mouse.bind()
    }

    // Enumerates all displays via DisplayManager; picks the first non-default display as the
    // target (glasses). Updates state with the display list and connection flags.
    // Also calls setDisplay on MouseService so key/cursor events reach the right display.
    fun refresh() {
        val allDisplays = displayManager.displays.map { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            DisplayInfo(d.displayId, d.name ?: "Display ${d.displayId}", m.widthPixels, m.heightPixels)
        }

        // Pick the external display: prefer any non-default display,
        // fall back to presentation category.
        val external = allDisplays.firstOrNull { it.id != Display.DEFAULT_DISPLAY }
            ?: run {
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    .firstOrNull()
                    ?.let { d ->
                        val m = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        d.getMetrics(m)
                        DisplayInfo(d.displayId, d.name ?: "Presentation", m.widthPixels, m.heightPixels)
                    }
            }

        val available = mouse.hasShizuku()
        val permitted = mouse.hasPermission()
        mouse.setPointerHidden(_state.value.pocketInputLocked)
        if (permitted && !mouse.isConnected) mouse.bind()

        if (external != null) {
            mouse.setDisplay(external.id, external.width, external.height)
        } else {
            mouse.clearDisplay()
        }
        val ready = permitted && mouse.isConnected && mouse.isDisplayReady
        if (external?.id != _state.value.targetDisplay?.id || !ready) {
            imePolicyJob?.cancel()
            appliedImePolicy = null
            pendingImePolicy = null
            smoothDx = 0f; smoothDy = 0f; pinchAccum = 0f
        }

        _state.update {
            it.copy(
                shizukuAvailable = available,
                shizukuPermission = permitted,
                mouseReady = ready,
                allDisplays = allDisplays,
                targetDisplay = external,
                dexKeyboardActive = ready && appliedImePolicy?.second == IME_POLICY_FALLBACK,
                dexKeyboardError = ready && it.targetDisplay?.id == external?.id && it.dexKeyboardError,
                touchMode = if (ready) it.touchMode else TouchMode.IDLE,
            )
        }
        if (external != null && ready) applyImePolicy(external.id)
    }

    // Opens the Shizuku permission dialog so the user can grant shell-uid access.
    fun requestShizukuPermission() = mouse.requestPermission()

    // Applies the IME policy matching the dexKeyboardEnabled preference to the external
    // display: fallback → keyboard appears on the phone, local → on the glasses.
    // Runs on IO because this Binder call returns a result. dexKeyboardActive is set
    // only when the fallback policy actually took effect on this build.
    private fun applyImePolicy(displayId: Int) {
        val policy = if (_state.value.dexKeyboardEnabled && !_state.value.pocketMode)
            IME_POLICY_FALLBACK else IME_POLICY_LOCAL
        val requested = displayId to policy
        if (!mouse.isDisplayReady || appliedImePolicy == requested ||
            (pendingImePolicy == requested && imePolicyJob?.isActive == true)) return
        imePolicyJob?.cancel()
        pendingImePolicy = requested
        imePolicyJob = viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { mouse.setImePolicy(displayId, policy) }
            val currentPolicy = if (_state.value.dexKeyboardEnabled && !_state.value.pocketMode)
                IME_POLICY_FALLBACK else IME_POLICY_LOCAL
            if (_state.value.targetDisplay?.id != displayId || !mouse.isDisplayReady ||
                currentPolicy != policy) return@launch
            appliedImePolicy = if (ok) displayId to policy else null
            pendingImePolicy = null
            _state.update { it.copy(dexKeyboardActive = ok && policy == IME_POLICY_FALLBACK,
                dexKeyboardError = !ok && policy == IME_POLICY_FALLBACK) }
        }
    }

    // Input: raw pixel deltas from the touch event.
    // Applies velocity-adaptive exponential smoothing (heavy for slow/fine moves,
    // light for fast moves) before scaling by sensitivity. This suppresses finger
    // tremor on precise movements without adding noticeable lag on fast sweeps.
    fun moveCursor(rawDx: Float, rawDy: Float) {
        if (!_state.value.touchpadInputEnabled) return
        val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)
        val alpha = (speed / 8f).coerceIn(0.30f, 0.85f)
        smoothDx = alpha * rawDx + (1f - alpha) * smoothDx
        smoothDy = alpha * rawDy + (1f - alpha) * smoothDy

        val state = _state.value
        val (dx, dy) = state.rotateInput(
            smoothDx * state.sensitivity, smoothDy * state.sensitivity,
        )
        if (dx == 0f && dy == 0f) return
        mouse.moveMouse(dx, dy)

        // MouseService owns the cursor position. Publishing unused coordinates here
        // used to recompose the whole phone/settings UI on every touch sample.
    }

    // Tracks gesture mode and drains buffered input at gesture boundaries.
    // Resets the smoothing filter on IDLE so the decaying tail doesn't bleed into the next touch.
    fun setTouchMode(mode: TouchMode) {
        if (mode == TouchMode.IDLE) {
            mouse.flushMovement()
            smoothDx = 0f; smoothDy = 0f
            pinchAccum = 0f
        }
        // The scroll gesture is over: send the tail still held back by the IPC pacing.
        if (mode != TouchMode.SCROLL) mouse.flushScroll()
        _state.update { it.copy(touchMode = mode) }
    }

    // MouseService clicks at its current cursor; legacy x/y arguments are ignored.
    fun performClick() { if (_state.value.touchpadInputEnabled) mouse.click(0f, 0f) }
    fun performRightClick() { if (_state.value.touchpadInputEnabled) mouse.rightClick(0f, 0f) }

    // Moonlight handles this locally: releases capture to Android, or recaptures it.
    fun toggleMoonlightCapture() {
        if (!_state.value.touchpadInputEnabled) return
        mouse.toggleMoonlightCapture()
    }

    // Presses BTN_LEFT without releasing; moveCursor calls while held extend a text selection.
    fun startSelectDrag() { if (_state.value.touchpadInputEnabled) mouse.mouseDown() }

    // System navigation/cancellation must release a held button without copying text.
    fun cancelSelectDrag() = mouse.mouseUp()

    // Releases BTN_LEFT to end the selection drag, then immediately injects Ctrl+C to copy
    // the selection into the clipboard before any cursor movement can clear it.
    fun endSelectDrag() {
        mouse.mouseUp()
        if (_state.value.touchpadInputEnabled) mouse.pressKeyWithCtrl(android.view.KeyEvent.KEYCODE_C)
    }

    // Turns finger travel into the distance the view should scroll, scaled by scrollSpeed.
    // Natural scroll (the default): content follows the fingers, so fingers moving down/right
    // scroll the view up/left. Off: the view scrolls the way the fingers move, like a wheel.
    fun performScroll(dx: Float, dy: Float) {
        if (!_state.value.touchpadInputEnabled) return
        val state = _state.value
        val speed = state.scrollSpeed
        val dir = if (state.naturalScroll) -1f else 1f
        val (rotatedDx, rotatedDy) = state.rotateInput(dx, dy)
        mouse.scroll(rotatedDx * speed * dir, rotatedDy * speed * dir)
        _state.update { it.copy(touchMode = TouchMode.SCROLL) }
    }

    // Forwards an Android keycode to MouseService for injection on the glasses display.
    fun pressKey(linuxKeyCode: Int) { if (_state.value.touchpadInputEnabled) mouse.pressKey(linuxKeyCode) }
    // Converts text to key events and injects them to the focused window on the glasses display.
    fun typeText(text: String) { if (_state.value.touchpadInputEnabled) mouse.typeText(text) }

    // Input: dDist — span change in pixels this frame (positive = spreading = zoom in).
    // 200 px of spread is one wheel notch of Ctrl+scroll (about 10 % zoom), sent in
    // PINCH_STEP_PX slices as fractions of a notch so the zoom tracks the fingers instead
    // of jumping a whole notch at a time. Content-level zoom; the system font scale is untouched.
    fun pinchZoom(dDist: Float) {
        if (!_state.value.touchpadInputEnabled) return
        pinchAccum += dDist
        val steps = (pinchAccum / PINCH_STEP_PX).toInt()
        if (steps != 0) {
            pinchAccum -= steps * PINCH_STEP_PX
            mouse.ctrlScroll(steps * PINCH_STEP_PX / PINCH_PX_PER_NOTCH)
        }
    }

    // Settings state updaters — each writes one field into TouchpadState and persists it
    // so tuned values survive app restarts.
    fun setSensitivity(v: Float) {
        prefs.edit().putFloat("sensitivity", v).apply()
        _state.update { it.copy(sensitivity = v) }
    }
    fun setScrollSpeed(v: Float) {
        prefs.edit().putFloat("scroll_speed", v).apply()
        _state.update { it.copy(scrollSpeed = v) }
    }
    fun setNaturalScroll(v: Boolean) {
        prefs.edit().putBoolean("natural_scroll", v).apply()
        _state.update { it.copy(naturalScroll = v) }
    }
    fun setAxisRotation(rotation: AxisRotation) {
        resetTouchpadInput()
        prefs.edit().putInt("axis_rotation_degrees", rotation.degrees).remove("calibrated_rotation_degrees").apply()
        _state.update { it.copy(axisRotation = rotation, calibratedRotationDegrees = null) }
    }
    fun startOrientationCalibration() {
        resetTouchpadInput()
        _state.update { it.copy(calibratingOrientation = true, touchMode = TouchMode.IDLE) }
    }
    fun finishOrientationCalibration(degrees: Float? = null) {
        if (!_state.value.calibratingOrientation) return
        if (degrees != null && (!degrees.isFinite() || degrees !in -180f..180f)) return
        resetTouchpadInput()
        if (degrees != null) prefs.edit().putFloat("calibrated_rotation_degrees", degrees).apply()
        _state.update { it.copy(calibratingOrientation = false,
            calibratedRotationDegrees = degrees ?: it.calibratedRotationDegrees) }
    }
    // Seconds of idle time before the touchpad UI is scrimmed; 0 disables dimming entirely.
    // MainActivity observes this and restarts its idle timer when it changes.
    fun setDimDelay(sec: Int) {
        prefs.edit().putInt("dim_delay_sec", sec).apply()
        _state.update { it.copy(dimDelaySec = sec) }
    }
    fun setDexKeyboard(v: Boolean) {
        prefs.edit().putBoolean("dex_keyboard", v).apply()
        _state.update { it.copy(dexKeyboardEnabled = v, dexKeyboardError = false) }
        _state.value.targetDisplay?.let { applyImePolicy(it.id) }
    }
    fun setBlackTouchpad(enabled: Boolean) {
        prefs.edit().putBoolean("black_touchpad", enabled).apply()
        _state.update { it.copy(blackTouchpad = enabled) }
    }
    fun setPocketMode(enabled: Boolean) = updatePocketMode(enabled, closeSettings = false)

    private fun resetTouchpadInput() {
        setDragReady(false)
        mouse.cancelPendingInput()
        mouse.mouseUp()
        smoothDx = 0f; smoothDy = 0f; pinchAccum = 0f
    }

    private fun updatePocketMode(enabled: Boolean, closeSettings: Boolean) {
        val changed = _state.value.pocketMode != enabled
        // Discard buffered travel and release a possible drag before closing the gate.
        resetTouchpadInput()
        prefs.edit().putBoolean("pocket_mode", enabled).apply()
        _state.update { it.copy(pocketMode = enabled,
            showSettings = it.showSettings && !closeSettings, touchMode = TouchMode.IDLE) }
        mouse.setPointerHidden(_state.value.pocketInputLocked)
        _state.value.targetDisplay?.let { applyImePolicy(it.id) }
        if (changed) _pocketModeChanges.tryEmit(enabled)
    }
    fun togglePocketMode(): Boolean {
        val locked = !_state.value.pocketMode
        updatePocketMode(locked, closeSettings = true)
        return locked
    }
    fun toggleSettings() = setSettingsOpen(!_state.value.showSettings)
    fun openSettings() = setSettingsOpen(true)
    fun closeSettings() = setSettingsOpen(false)

    private fun setSettingsOpen(open: Boolean) {
        if (_state.value.showSettings == open && !_state.value.calibratingOrientation) return
        resetTouchpadInput()
        _state.update { it.copy(showSettings = open, calibratingOrientation = false, touchMode = TouchMode.IDLE) }
        mouse.setPointerHidden(_state.value.pocketInputLocked)
    }

    // Cleans up the display listener and mouse service when the
    // ViewModel is destroyed (e.g. app process ends or activity is permanently finished).
    override fun onCleared() {
        imePolicyJob?.cancel()
        displayManager.unregisterDisplayListener(displayListener)
        // Restore the glasses-side IME before the service goes away. Synchronous call:
        // viewModelScope is already cancelled by the time onCleared runs.
        if (appliedImePolicy?.second == IME_POLICY_FALLBACK) {
            _state.value.targetDisplay?.let { mouse.setImePolicy(it.id, IME_POLICY_LOCAL) }
        }
        mouse.destroy()
    }
}
