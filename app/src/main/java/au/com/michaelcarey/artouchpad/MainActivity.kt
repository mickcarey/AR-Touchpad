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

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import android.os.Bundle
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.view.Display
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
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

    private val displayStatus by lazy { DisplayStatus(this) }
    private val uatNotice by lazy { DisplayStatus(this, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
        "AR Touchpad UAT notice") }
    private val noticePrefs by lazy { getSharedPreferences("uat_notice", Context.MODE_PRIVATE) }
    private val pendingNotice = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.updateLaunchpad { it.copy(roomMeter = granted) }
        if (!granted) Toast.makeText(this, "Allow microphone access in app permissions to use the room meter.", Toast.LENGTH_LONG).show()
    }

    private fun setRoomMeter(enabled: Boolean) {
        if (enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else viewModel.updateLaunchpad { it.copy(roomMeter = enabled) }
    }

    private val idleHandler = Handler(Looper.getMainLooper())
    private var isDimmed by mutableStateOf(false)
    private var pointerDown = false
    private var lastInteractionMs = 0L
    private var dimScheduled = false
    private var settingsOverlay: SettingsOverlay? = null
    private var settingsOnPhone by mutableStateOf(false)
    private var switchingBuild = false
    private var protectPhoneGestures = false
    private var topEdgeBarControl: WindowInsetsAnimationController? = null
    private var topEdgeStroke = false
    private var topEdgeDismissed = false
    private val dismissTransientBars = Runnable { viewModel.mouse.hideTransientPhoneBars() }

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
        if (BuildConfig.BUILD_TYPE == "debug") {
            queueUatNotice(intent)
            observeUatNotice()
        }
        observeDisplayStatus()
        setContent {
            val state by viewModel.state.collectAsState()
            ARTouchpadTheme(state.launchpad) {
                Box(modifier = Modifier.fillMaxSize()) {
                    TouchpadScreen(viewModel, settingsOnPhone, ::switchBuild, ::setRoomMeter, ::clickTouchpad, onShowPhoneOverview = {
                        // Overview can keep this activity resumed for its live preview.
                        protectPhoneGestures = false
                        viewModel.mouse.setNotificationShadeBlocked(false)
                        viewModel.mouse.showPhoneOverview()
                    })
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.BUILD_TYPE == "debug") queueUatNotice(intent)
    }

    private fun queueUatNotice(intent: Intent) {
        val requested = intent.getBooleanExtra("uat_ready", false)
        val saved = noticePrefs.getLong("pending", 0L)
        if (!requested && saved == 0L) return
        val installed = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
        val pending = installed.takeIf { (requested || saved == installed) && noticePrefs.getLong("shown", 0L) != installed } ?: 0L
        noticePrefs.edit().putLong("pending", pending).apply()
        pendingNotice.value = pending
    }

    private fun observeUatNotice() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val targets = viewModel.state.map { it.targetDisplay?.id to it.mouseReady }.distinctUntilChanged()
                combine(pendingNotice, targets, ::Pair).collectLatest { (installed, target) ->
                    if (installed == 0L || installed == noticePrefs.getLong("shown", 0L)) return@collectLatest
                    val display = target.first?.let { getSystemService(DisplayManager::class.java).getDisplay(it) } ?: return@collectLatest
                    if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                        if (!target.second) return@collectLatest
                        withContext(Dispatchers.IO) { viewModel.mouse.enableSettingsOverlay() }
                    }
                    val installedAt = android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(java.util.Date(installed))
                    if (uatNotice.show(display, "AR Touchpad Debug ready for UAT · $installedAt", viewModel.state.value.launchpad)) {
                        try {
                            kotlinx.coroutines.delay(12_000)
                            noticePrefs.edit().putLong("shown", installed).remove("pending").apply()
                            pendingNotice.value = 0L
                        } finally { uatNotice.hide() }
                    }
                }
            }
        }
    }

    private data class StatusOptions(val displayId: Int?, val locked: Boolean, val prefs: LaunchpadPreferences)

    private fun observeDisplayStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.map { it.launchpad.roomMeter && (it.showSettings || (it.mouseReady && it.externalDisplayConnected)) }
                        .distinctUntilChanged().collectLatest { enabled ->
                            viewModel.quietReference.value = null
                            viewModel.roomLevel.value = RoomLevel()
                            if (!enabled) return@collectLatest
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                viewModel.updateLaunchpad { it.copy(roomMeter = false) }
                                return@collectLatest
                            }
                            roomLevels(applicationContext).catch { error ->
                                if (error is CancellationException) throw error
                                emit(RoomLevel(status = "Phone mic unavailable"))
                            }.collect { viewModel.roomLevel.value = it }
                        }
                }
                try {
                    viewModel.state.map { state ->
                        val displayId = state.targetDisplay?.id?.takeIf { !state.showSettings && state.mouseReady }
                        StatusOptions(displayId, state.pocketInputLocked, state.launchpad)
                    }.distinctUntilChanged().collectLatest { options ->
                        val display = options.displayId?.let { getSystemService(DisplayManager::class.java).getDisplay(it) }
                        if (display == null || (!options.locked && !options.prefs.roomMeter)) {
                            displayStatus.hide()
                            return@collectLatest
                        }
                        if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                            withContext(Dispatchers.IO) { viewModel.mouse.enableSettingsOverlay() }
                        }
                        if (options.prefs.roomMeter) combine(viewModel.roomLevel, viewModel.quietReference, ::Pair).collect { (level, quiet) ->
                            val message = listOfNotNull("🔒".takeIf { options.locked }, level.label(quiet)).joinToString("   ")
                            displayStatus.show(display, message, options.prefs)
                        } else displayStatus.show(display, "🔒", options.prefs)
                    }
                } finally { displayStatus.hide() }
            }
        }
    }

    private fun updateSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val state = viewModel.state.value
            val activePad = state.mouseReady && state.externalDisplayConnected &&
                !settingsOnPhone && !state.calibratingOrientation
            viewModel.mouse.setNotificationShadeBlocked(protectPhoneGestures && activePad)
            // Sticky immersive allows gesture exclusion along the full height of both edges.
            systemBarsBehavior = if (activePad)
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

    internal fun clickTouchpad() {
        if (viewModel.state.value.touchpadInputEnabled) settingsOverlay?.expectTouchpadClick()
        viewModel.performClick()
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
            val newOverlay = SettingsOverlay(this, display, viewModel, ::switchBuild, ::setRoomMeter)
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

    private fun switchBuild(target: TouchpadBuild) {
        if (switchingBuild) return
        switchingBuild = true
        lifecycleScope.launch {
            var launched = false
            try {
                viewModel.persistInputSettings()
                viewModel.resetTouchpadInput()
                if (!viewModel.mouse.pauseForBuildSwitch()) {
                    Toast.makeText(this@MainActivity, "Touchpad is busy. Try switching again.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !viewModel.state.value.showSettings) {
                    return@launch
                }
                val intent = target.launchIntent(this@MainActivity) ?: throw ActivityNotFoundException()
                // Both the phone UI and the external overlay hand off the phone's task.
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                startActivity(intent, options.toBundle())
                launched = true
                viewModel.closeSettings()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "${target.name} is unavailable.", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(this@MainActivity, "Android could not open ${target.name}.", Toast.LENGTH_SHORT).show()
            } finally {
                switchingBuild = false
                if (!launched && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.mouse.setActive(true)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    // Every touch — including ACTION_MOVE — counts as activity, so a long continuous drag
    // or scroll keeps the screen lit. Activity.onUserInteraction() alone is not enough:
    // it only fires on the initial down, so a slow 30 s drag would dim mid-gesture.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Waking the pad can reset bar visibility. Do that before taking gesture control.
        noteInteraction()
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDown = true
                if (!protectPhoneGestures && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    protectPhoneGestures = true
                    updateSystemBars()
                }
                val state = viewModel.state.value
                val decor = window.decorView
                // A hidden status bar can report a zero mandatory-gesture inset. Its reveal
                // target still occupies the physical status-bar strip on the phone.
                val top = decor.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0
                if (ev.y < top && state.mouseReady && !settingsOnPhone && !state.calibratingOrientation) {
                    topEdgeStroke = true
                    topEdgeDismissed = false
                    window.insetsController?.controlWindowInsetsAnimation(
                        WindowInsets.Type.systemBars(), -1, null, null, topEdgeListener)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (topEdgeStroke && !topEdgeDismissed && ev.y >= window.decorView.rootWindowInsets
                        .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top) {
                    topEdgeDismissed = true
                    window.decorView.postOnAnimation(dismissTransientBars)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                finishTopEdgeStroke()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // A pull at the top can reveal both system bars together. Control both surfaces
    // for this stroke so the transient bars do not take the next touch.
    private val topEdgeListener = object : WindowInsetsAnimationControlListener {
        override fun onReady(controller: WindowInsetsAnimationController, types: Int) {
            if (topEdgeStroke) {
                topEdgeBarControl = controller
                controller.setInsetsAndAlpha(controller.hiddenStateInsets, 0f, 0f)
            } else controller.finish(settingsOnPhone || viewModel.state.value.calibratingOrientation)
        }
        override fun onFinished(controller: WindowInsetsAnimationController) = releaseControl(controller)
        override fun onCancelled(controller: WindowInsetsAnimationController?) = releaseControl(controller)

        private fun releaseControl(controller: WindowInsetsAnimationController?) {
            if (topEdgeBarControl === controller) topEdgeBarControl = null
            // SystemUI can take the bar back mid-drag. Reapply the hidden state after
            // the current animation callback finishes if this stroke is still active.
            if (topEdgeStroke) window.decorView.postOnAnimation {
                if (topEdgeStroke) window.insetsController?.hide(WindowInsets.Type.systemBars())
            }
        }
    }

    private fun finishTopEdgeStroke() {
        if (!topEdgeStroke) return
        topEdgeStroke = false
        window.decorView.removeCallbacks(dismissTransientBars)
        topEdgeBarControl?.takeIf { it.isReady }?.finish(settingsOnPhone || viewModel.state.value.calibratingOrientation)
        topEdgeBarControl = null
        // A transient bar can outlive the stroke and intercept the next DOWN. Take
        // control again and finish hidden as soon as Android hands the bar back.
        if (protectPhoneGestures && !settingsOnPhone && !viewModel.state.value.calibratingOrientation) {
            viewModel.mouse.hideTransientPhoneBars()
            window.insetsController?.controlWindowInsetsAnimation(WindowInsets.Type.systemBars(),
                0, null, null, topEdgeListener)
        }
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
        protectPhoneGestures = true
        viewModel.refresh()
        noteInteraction()
        updateSystemBars()
    }

    // Drops the scrim and the pending dim so the touchpad never comes back to the
    // foreground already dark.
    override fun onPause() {
        protectPhoneGestures = false
        finishTopEdgeStroke()
        viewModel.mouse.setNotificationShadeBlocked(false)
        super.onPause()
        idleHandler.removeCallbacks(dimRunnable)
        dimScheduled = false
        isDimmed = false
        pointerDown = false
    }

    override fun onStop() {
        viewModel.persistInputSettings()
        viewModel.resetTouchpadInput()
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
