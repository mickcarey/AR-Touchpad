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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import au.com.michaelcarey.artouchpad.ui.TouchpadScreen
import au.com.michaelcarey.artouchpad.ui.theme.ARTouchpadTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The touchpad UI is covered by a black scrim after TouchpadState.dimDelaySec of no input
// (user-configurable in the settings panel; 0 disables it). The screen is never allowed to
// sleep while this activity is on top (FLAG_KEEP_SCREEN_ON) — the user is looking through
// the glasses, and a sleeping phone means a dead touchpad.
//
// The scrim is deliberately in-app rather than a WindowManager.LayoutParams.screenBrightness
// override: that override is handed to PowerManagerService and applied to every display
// power group, so on a phone driving XR glasses it dims (and can blank) the glasses too.

// Opacity of the black scrim drawn over the touchpad when idle. High enough to blank the
// OLED almost completely (which is where the power saving comes from), low enough that the
// UI is still readable if the user glances down at the phone.
private const val DIM_ALPHA = 0.92f

// Single-activity entry point. Hosts the Compose UI and owns the ViewModel lifecycle.
class MainActivity : ComponentActivity() {

    private val viewModel: TouchpadViewModel by viewModels()

    private val idleHandler = Handler(Looper.getMainLooper())
    private var isDimmed by mutableStateOf(false)
    private var pointerDown = false
    private var lastInteractionMs = 0L
    private var dimScheduled = false
    private var settingsOverlay: SettingsOverlay? = null
    private var settingsOnPhone by mutableStateOf(false)

    // FLAG_KEEP_SCREEN_ON only covers the phone's own display; this covers the glasses.
    private val glassesWakeLock by lazy { DisplayWakeLock(applicationContext) }

    // A finger resting motionless emits no ACTION_MOVE, so a long press or a paused drag
    // looks identical to idleness. Defer while any pointer is still down rather than
    // dimming out from under an in-flight gesture.
    private val dimRunnable = object : Runnable {
        override fun run() {
            dimScheduled = false
            val remaining = dimDelayMs - (SystemClock.uptimeMillis() - lastInteractionMs)
            if (remaining > 0) {
                scheduleDim(remaining)
                return
            }
            if (pointerDown) {
                scheduleDim()
                return
            }
            isDimmed = true
            updateSystemBars()
        }
    }

    // Idle timeout from the live setting, in ms; 0 (the "Never" chip) disables dimming.
    private val dimDelayMs: Long
        get() = viewModel.state.value.dimDelaySec.toLong() * 1000L

    // Sets up edge-to-edge display and mounts the full-screen TouchpadScreen composable.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        updateSystemBars()
        // Applies only while this window is in front, so the phone resumes its normal
        // timeout as soon as the touchpad is backgrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A trackpad is an input peripheral, not the keyboard-focus target. Otherwise
        // touching the phone takes focus from the external app and dismisses its menus
        // before our gesture recognizer even sees movement. Only phone-side setup/settings
        // need focus; an external settings window leaves the phone acting as a touchpad.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map {
                    Triple(it.mouseReady to it.settingsRequirePhone, it.targetDisplay?.id,
                        it.showSettings to it.calibratingOrientation)
                }.distinctUntilChanged().collect { (readiness, displayId, phoneUi) ->
                    val (showSettings, calibrating) = phoneUi
                    val (mouseReady, requirePhone) = readiness
                    if (!showSettings || requirePhone) dismissSettingsOverlay()
                    settingsOnPhone = showSettings && (requirePhone || !showSettingsOnDisplay(displayId))
                    val trackpadActive = mouseReady && displayId != null && !settingsOnPhone && !calibrating
                    if (calibrating) noteInteraction()
                    val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    // ALT_FOCUSABLE_IM keeps this non-focusable window below the phone
                    // keyboard when the external display uses the fallback IME policy.
                    window.setFlags(if (trackpadActive) flags else 0, flags)
                    updateSystemBars()
                }
            }
        }
        // Changing the delay in settings restarts the countdown against the new value, so
        // picking "Never" clears a pending dim and picking a shorter delay takes effect now.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { it.dimDelaySec }.distinctUntilChanged().collect {
                    idleHandler.removeCallbacks(dimRunnable)
                    dimScheduled = false
                    noteInteraction()
                }
            }
        }
        // Hold the glasses awake for as long as the touchpad is showing, following the target
        // display as it comes and goes (its id changes on every replug). Released when the
        // touchpad leaves the screen, so both displays fall back to their normal timeout.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.state.map { it.targetDisplay?.id }.distinctUntilChanged().collect {
                        viewModel.mouse.dismissModeFeedback()
                        glassesWakeLock.holdFor(it)
                    }
                } finally {
                    glassesWakeLock.release()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dragReadyChanges.collect { ready ->
                    viewModel.mouse.setDragReady(ready)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pocketModeChanges.collect { locked ->
                    val displayId = viewModel.state.value.targetDisplay?.id
                    val display = displayId?.let { getSystemService(DisplayManager::class.java).getDisplay(it) }
                    if (display != null) {
                        val badge = modeFeedbackBadge(locked, createDisplayContext(display).resources.displayMetrics.density)
                        try { viewModel.mouse.showModeFeedback(badge) } finally { badge.recycle() }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { it.blackTouchpad to it.pocketInputLocked }.distinctUntilChanged().collect {
                    updateSystemBars()
                }
            }
        }
        setContent {
            val state by viewModel.state.collectAsState()
            ARTouchpadTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TouchpadScreen(viewModel = viewModel, settingsOnPhone = settingsOnPhone)
                    // A draw-only black surface keeps the digitizer and gestures active.
                    // Phone-side recovery/settings must remain visible when disconnected.
                    val blackPad = state.blackTouchpad && state.mouseReady &&
                        state.externalDisplayConnected && !settingsOnPhone
                    if ((isDimmed || blackPad) && !state.calibratingOrientation) {
                        // Draw-only overlay: no pointerInput modifier, so touches fall
                        // straight through to the touchpad surface underneath and the
                        // gesture that wakes the screen still counts as a gesture.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = if (blackPad) 1f else DIM_ALPHA)),
                        )
                    }
                }
            }
        }
    }

    private fun updateSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Unlike sticky immersive mode, DEFAULT allows Home/Overview gestures
            // directly while the navigation bar is hidden.
            val state = viewModel.state.value
            systemBarsBehavior = if (state.pocketInputLocked && !settingsOnPhone)
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            isAppearanceLightNavigationBars = false
            val blackPad = state.blackTouchpad && state.mouseReady &&
                state.externalDisplayConnected && !settingsOnPhone
            // System bars live outside the Compose scrim. If Android transiently reveals
            // the status bar over a dark pad, avoid a bright header on an otherwise black phone.
            isAppearanceLightStatusBars = !settingsOnPhone && !state.calibratingOrientation && (blackPad || isDimmed)
            if (settingsOnPhone || state.calibratingOrientation) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private suspend fun showSettingsOnDisplay(displayId: Int?): Boolean {
        if (displayId == null) { dismissSettingsOverlay(); return false }
        settingsOverlay?.let { if (it.display.displayId == displayId && it.isShowing) return true }
        dismissSettingsOverlay()
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val allowed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                viewModel.mouse.enableSettingsOverlay()
            }
            if (!allowed || !android.provider.Settings.canDrawOverlays(this)) return false
        }
        if (!viewModel.state.value.showSettings || viewModel.state.value.targetDisplay?.id != displayId) return false
        var overlay: SettingsOverlay? = null
        return try {
            val newOverlay = SettingsOverlay(this, display, viewModel)
            overlay = newOverlay
            newOverlay.setOnDismissListener {
                if (settingsOverlay === newOverlay) {
                    settingsOverlay = null
                    viewModel.closeSettings()
                }
            }
            newOverlay.show()
            settingsOverlay = newOverlay
            true
        } catch (e: RuntimeException) {
            Log.w("MainActivity", "External settings unavailable; showing settings on phone", e)
            overlay?.setOnDismissListener(null)
            overlay?.dismiss()
            false
        }
    }

    private fun dismissSettingsOverlay() {
        settingsOverlay?.setOnDismissListener(null)
        settingsOverlay?.dismiss()
        settingsOverlay = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    // Every touch — including ACTION_MOVE — counts as activity, so a long continuous drag
    // or scroll keeps the screen lit. Activity.onUserInteraction() alone is not enough:
    // it only fires on the initial down, so a slow 30 s drag would dim mid-gesture.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN -> pointerDown = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerDown = false
        }
        noteInteraction()
        return super.dispatchTouchEvent(ev)
    }

    // Public Window.Callback override; Core's implementation carries a library-only
    // annotation, but forwarding this framework callback must still call super.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteInteraction()
        return super.dispatchKeyEvent(event)
    }

    // Clears the scrim if present and restarts the idle countdown. Runs on every touch
    // event (~120 Hz during a drag), so it stays cheap: state is only written on an actual
    // dim→bright transition. The deadline checks the timestamp, avoiding handler churn.
    private fun noteInteraction() {
        lastInteractionMs = SystemClock.uptimeMillis()
        if (isDimmed) {
            isDimmed = false
            updateSystemBars()
        }
        if (!dimScheduled) scheduleDim()
    }

    private fun scheduleDim(delay: Long = dimDelayMs) {
        if (delay > 0) {
            dimScheduled = true
            idleHandler.postDelayed(dimRunnable, delay)
        }
    }

    // Re-checks display and Shizuku state when returning from Settings or the permission
    // dialog, so the UI reflects any changes the user made while away.
    override fun onStart() {
        super.onStart()
        viewModel.mouse.setActive(true)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        noteInteraction()
        updateSystemBars()
    }

    // Drops the scrim and the pending dim so the touchpad never comes back to the
    // foreground already dark.
    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(dimRunnable)
        dimScheduled = false
        isDimmed = false
        pointerDown = false
    }

    override fun onStop() {
        viewModel.persistInputSettings()
        viewModel.cancelSelectDrag()
        viewModel.mouse.setActive(false)
        viewModel.mouse.dismissModeFeedback()
        dismissSettingsOverlay()
        viewModel.closeSettings()
        super.onStop()
    }

    override fun onDestroy() {
        viewModel.mouse.dismissModeFeedback()
        dismissSettingsOverlay()
        super.onDestroy()
    }
}
