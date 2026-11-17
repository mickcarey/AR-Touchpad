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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.pgratz.artouchpad.ui.TouchpadScreen
import com.pgratz.artouchpad.ui.theme.ARTouchpadTheme
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
    private var settingsPresentation: SettingsPresentation? = null
    private var settingsOnPhone by mutableStateOf(false)

    // FLAG_KEEP_SCREEN_ON only covers the phone's own display; this covers the glasses.
    private val glassesWakeLock by lazy { DisplayWakeLock(applicationContext) }

    // A finger resting motionless emits no ACTION_MOVE, so a long press or a paused drag
    // looks identical to idleness. Defer while any pointer is still down rather than
    // dimming out from under an in-flight gesture.
    private val dimRunnable = object : Runnable {
        override fun run() {
            if (pointerDown) {
                scheduleDim()
                return
            }
            isDimmed = true
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
                    Triple(it.mouseReady, it.targetDisplay?.id, it.showSettings)
                }.distinctUntilChanged().collect { (mouseReady, displayId, showSettings) ->
                    if (!showSettings || !mouseReady) dismissSettingsPresentation()
                    settingsOnPhone = showSettings && (!mouseReady || !showSettingsOnDisplay(displayId))
                    val trackpadActive = mouseReady && displayId != null && !settingsOnPhone
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
                        glassesWakeLock.holdFor(it)
                    }
                } finally {
                    glassesWakeLock.release()
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
                    if (isDimmed || blackPad) {
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
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (settingsOnPhone) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSettingsOnDisplay(displayId: Int?): Boolean {
        if (displayId == null) { dismissSettingsPresentation(); return false }
        settingsPresentation?.let { if (it.display.displayId == displayId && it.isShowing) return true }
        dismissSettingsPresentation()
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        var presentation: SettingsPresentation? = null
        return try {
            val newPresentation = SettingsPresentation(this, display, viewModel)
            presentation = newPresentation
            newPresentation.setOnDismissListener {
                if (settingsPresentation === newPresentation) {
                    settingsPresentation = null
                    viewModel.closeSettings()
                }
            }
            newPresentation.show()
            settingsPresentation = newPresentation
            true
        } catch (e: RuntimeException) {
            Log.w("MainActivity", "External settings unavailable; showing settings on phone", e)
            presentation?.setOnDismissListener(null)
            presentation?.dismiss()
            false
        }
    }

    private fun dismissSettingsPresentation() {
        settingsPresentation?.setOnDismissListener(null)
        settingsPresentation?.dismiss()
        settingsPresentation = null
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
    // dim→bright transition, otherwise this is just a handler re-post.
    private fun noteInteraction() {
        isDimmed = false
        scheduleDim()
    }

    private fun scheduleDim() {
        idleHandler.removeCallbacks(dimRunnable)
        val delay = dimDelayMs
        if (delay > 0) idleHandler.postDelayed(dimRunnable, delay)
    }

    // Re-checks display and Shizuku state when returning from Settings or the permission
    // dialog, so the UI reflects any changes the user made while away.
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
        isDimmed = false
        pointerDown = false
    }

    override fun onStop() {
        dismissSettingsPresentation()
        viewModel.closeSettings()
        super.onStop()
    }

    override fun onDestroy() {
        dismissSettingsPresentation()
        super.onDestroy()
    }
}
