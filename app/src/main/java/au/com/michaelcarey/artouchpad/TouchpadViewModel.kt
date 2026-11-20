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

package au.com.michaelcarey.artouchpad

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TouchMode { IDLE, CURSOR, SCROLL, SELECT }

// WindowManager DISPLAY_IME_POLICY_* values, passed to IWindowManager.setDisplayImePolicy.
private const val IME_POLICY_LOCAL = 0     // IME on the display that owns the focused field
private const val IME_POLICY_FALLBACK = 1  // IME on the default display (phone) — DeX-style

// Idle-dim choices offered in the settings panel, in seconds; 0 means "never dim".
val DIM_DELAY_CHOICES_SEC = listOf(0, 15, 30, 60, 120, 300)
const val DEFAULT_DIM_DELAY_SEC = 30

data class DisplayInfo(val id: Int, val name: String, val width: Int, val height: Int, val uniqueId: String = name)

data class TouchpadState(
    val media: MediaState = MediaState(),
    val smoothing: Smoothing = Smoothing.LIGHT,
    val precision: Boolean = false,
    val autoCopy: Boolean = false,
    val dragLock: Boolean = false,
    val dragHeld: Boolean = false,
    val inputEpoch: Long = 0,
    val holdMs: Long = 600,
    val threeFingerAction: ThreeFingerAction = ThreeFingerAction.MOONLIGHT,
    val preferredDisplay: String? = null,
    val selectedProfile: UsageProfile = UsageProfile.DESKTOP,
    val connectionMessage: String = "Starting connection…",
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

    private val settingsStore = InputSettingsStore(prefs)

    private val _state = MutableStateFlow(TouchpadState(
        preferredDisplay = prefs.getString("preferred_display", null),
        selectedProfile = runCatching { UsageProfile.valueOf(prefs.getString("selected_profile", "DESKTOP")!!) }
            .getOrDefault(UsageProfile.DESKTOP),
        axisRotation = AxisRotation.fromDegrees(prefs.getInt("axis_rotation_degrees", 0)),
        calibratedRotationDegrees = if (prefs.contains("calibrated_rotation_degrees"))
            prefs.getFloat("calibrated_rotation_degrees", 0f).takeIf { it.isFinite() && it in -180f..180f } else null,
        dimDelaySec = prefs.getInt("dim_delay_sec", DEFAULT_DIM_DELAY_SEC),
        pocketMode = prefs.getBoolean("pocket_mode", false),
    ).withInputSettings(settingsStore.load()))
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
    private val motionFilter = MotionFilter()
    private var appliedImePolicy: Pair<Int, Int>? = null
    private var pendingImePolicy: Pair<Int, Int>? = null
    private var imePolicyJob: Job? = null
    private val imeMutex = Mutex()
    private val mediaMutex = Mutex()

    suspend fun refreshMediaState() {
        mediaMutex.withLock {
            val media = withContext(Dispatchers.IO) { mouse.mediaState() }
            _state.update { it.copy(media = media.copy(error = it.media.error)) }
        }
    }

    fun sendMediaControl(key: Int) {
        if (!_state.value.touchpadInputEnabled) return
        viewModelScope.launch {
            mediaMutex.withLock {
                val media = withContext(Dispatchers.IO) {
                    val sent = mouse.sendMediaControl(key)
                    mouse.mediaState().copy(error = if (sent) null else "Media control unavailable")
                }
                _state.update { it.copy(media = media) }
            }
        }
    }

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
            DisplayInfo(d.displayId, d.name ?: "Display ${d.displayId}", m.widthPixels, m.heightPixels,
                runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(Display::class.java, d, "getUniqueId") as String }
                    .getOrDefault("${d.name}:${m.widthPixels}x${m.heightPixels}"))
        }

        val external = selectDisplay(allDisplays, _state.value.preferredDisplay)

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
            motionFilter.reset(); pinchAccum = 0f
        }

        _state.update {
            it.copy(
                shizukuAvailable = available,
                shizukuPermission = permitted,
                mouseReady = ready,
                connectionMessage = when {
                    !available -> "Start Shizuku to connect the mouse."
                    !permitted -> "Grant Shizuku permission."
                    external == null -> "Connect an external display."
                    ready -> "Connected to ${external.name}"
                    else -> mouse.connectionMessage
                },
                dragHeld = ready && it.dragHeld,
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
            val ok = imeMutex.withLock {
                withContext(Dispatchers.IO) { mouse.setImePolicy(displayId, policy) }
            }
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
    // Time-based smoothing has consistent behavior across touch sampling rates.
    fun moveCursor(rawDx: Float, rawDy: Float, time: Long) {
        if (!_state.value.touchpadInputEnabled) return
        val state = _state.value
        val (dx, dy) = motionFilter.move(rawDx, rawDy, time, state.smoothing)
        sendMovement(dx, dy)
    }

    private fun sendMovement(x: Float, y: Float) {
        val state = _state.value
        val scale = state.sensitivity * if (state.precision) 0.3f else 1f
        val (dx, dy) = state.rotateInput(x * scale, y * scale)
        if (dx != 0f || dy != 0f) mouse.moveMouse(dx, dy)
    }

    // Tracks gesture mode and drains buffered input at gesture boundaries.
    // Resets the smoothing filter on IDLE so the decaying tail doesn't bleed into the next touch.
    fun setTouchMode(mode: TouchMode) {
        if (mode == TouchMode.IDLE) {
            val tail = motionFilter.finish()
            sendMovement(tail.first, tail.second)
            mouse.flushMovement()
            pinchAccum = 0f
        }
        // The scroll gesture is over: send the tail still held back by the IPC pacing.
        if (mode != TouchMode.SCROLL) mouse.finishScroll()
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
    fun cancelSelectDrag() {
        motionFilter.reset()
        mouse.cancelPendingInput()
        mouse.mouseUp()
        setDragHeld(false)
    }
    fun setDragHeld(held: Boolean) { _state.update { it.copy(dragHeld = held) } }

    // Ordinary drags only release the button. Copy is an explicit opt-in preference.
    fun endSelectDrag() {
        mouse.mouseUp()
        if (_state.value.touchpadInputEnabled && _state.value.autoCopy) mouse.pressKeyWithCtrl(android.view.KeyEvent.KEYCODE_C)
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
        _state.update { it.copy(sensitivity = InputSettings(sensitivity = v).validated().sensitivity) }
    }
    fun setScrollSpeed(v: Float) {
        _state.update { it.copy(scrollSpeed = InputSettings(scrollSpeed = v).validated().scrollSpeed) }
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

    fun resetTouchpadInput() {
        _state.update { it.copy(inputEpoch = it.inputEpoch + 1) }
        cancelSelectDrag()
        setDragReady(false)
        pinchAccum = 0f
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

    fun persistInputSettings() = settingsStore.save(_state.value.inputSettings())

    fun updateInputSettings(change: (InputSettings) -> InputSettings) {
        resetTouchpadInput()
        val settings = change(_state.value.inputSettings()).validated()
        settingsStore.save(settings)
        _state.update { it.withInputSettings(settings) }
        _state.value.targetDisplay?.let { applyImePolicy(it.id) }
    }

    fun applyProfile(profile: UsageProfile) {
        updateInputSettings { settingsStore.load(profile) }
        prefs.edit().putString("selected_profile", profile.name).apply()
        _state.update { it.copy(selectedProfile = profile) }
    }
    fun saveProfile(profile: UsageProfile) = settingsStore.save(_state.value.inputSettings(), profile)

    fun resetDefaults() {
        resetTouchpadInput()
        prefs.edit().clear().apply()
        val current = _state.value
        _state.value = TouchpadState(showSettings = true, allDisplays = current.allDisplays,
            targetDisplay = current.targetDisplay, mouseReady = current.mouseReady,
            inputEpoch = current.inputEpoch)
        mouse.setPointerHidden(false)
        refresh()
    }

    fun selectTargetDisplay(uniqueId: String?) {
        resetTouchpadInput()
        prefs.edit().putString("preferred_display", uniqueId).apply()
        _state.update { it.copy(preferredDisplay = uniqueId) }
        refresh()
    }

    fun retryConnection() { resetTouchpadInput(); mouse.retry(); refresh() }

    fun performThreeFingerAction() {
        if (!_state.value.touchpadInputEnabled) return
        when (_state.value.threeFingerAction) {
            ThreeFingerAction.MOONLIGHT -> toggleMoonlightCapture()
            ThreeFingerAction.PLAY_PAUSE -> sendMediaControl(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ThreeFingerAction.PRECISION -> updateInputSettings { it.copy(precision = !it.precision) }
            ThreeFingerAction.SETTINGS -> openSettings()
            ThreeFingerAction.NONE -> Unit
        }
    }

    // Cleans up the display listener and mouse service when the
    // ViewModel is destroyed (e.g. app process ends or activity is permanently finished).
    override fun onCleared() {
        imePolicyJob?.cancel()
        displayManager.unregisterDisplayListener(displayListener)
        persistInputSettings()
        // MouseService restores the original policies it owns before destroying its device.
        mouse.destroy()
    }
}
