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

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ShizukuMouseController {

    var isConnected = false
        private set

    var connectionMessage = "Connecting to Shizuku…"
        private set
    private var binding = false
    private var failed = false
    private var destroyed = false
    private var healthJob: Job? = null
    private var active = true
    private val bindTimeout = Runnable {
        if (binding && !isConnected) {
            binding = false; failed = true
            connectionMessage = "Shizuku did not respond. Check it is running, then retry."
            onStateChanged?.invoke()
        }
    }
    private var service: IMouseService? = null
    private var pointerHidden = false
    private var notificationShadeBlocked = false
    private var onStateChanged: (() -> Unit)? = null
    private val motionHandler = Handler(Looper.getMainLooper())
    private val movement = MotionBatcher(
        now = SystemClock::uptimeMillis,
        schedule = { task, delay -> motionHandler.postDelayed(task, delay); Unit },
        cancel = { motionHandler.removeCallbacks(it) },
        send = { dx, dy -> input { it.moveMouse(dx, dy) } },
    )
    private val displayBinding = DisplayBinding()
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var displayJob: Job? = null
    val isDisplayReady get() = displayBinding.isReady
    private val scrolling = ScrollScheduler(SystemClock::uptimeMillis,
        { task, delay -> motionHandler.postDelayed(task, delay); Unit },
        { motionHandler.removeCallbacks(it) }, { x, y -> input { it.scroll(x, y) } })

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, MouseService::class.java.name)
    )
        .processNameSuffix("mouse")
        .daemon(false)
        .version(36)  // phone notification shade and Overview

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (destroyed) return
            // Shizuku can report an already-connected service again. Keep its motion
            // state and display association instead of resetting a live pointer.
            if (isConnected && service?.asBinder() == binder) return
            binding = false; failed = false
            motionHandler.removeCallbacks(bindTimeout)
            displayJob?.cancel()
            displayBinding.invalidate()
            resetPendingInput()
            service = IMouseService.Stub.asInterface(binder)
            runCatching { service?.setPointerHidden(pointerHidden) }
            runCatching { service?.setNotificationShadeBlocked(notificationShadeBlocked) }
            isConnected = service != null
            connectionMessage = "Preparing mouse for the display…"
            startHealthChecks()
            onStateChanged?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (destroyed) return
            binding = false
            healthJob?.cancel()
            motionHandler.removeCallbacks(bindTimeout)
            connectionMessage = "Mouse service disconnected. Retry to reconnect."
            displayJob?.cancel()
            displayBinding.invalidate()
            resetPendingInput()
            service = null
            isConnected = false
            // Shizuku clears its old callback set after returning; bind on the next turn.
            motionHandler.post { if (!destroyed) onStateChanged?.invoke() }
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind()
        onStateChanged?.invoke()
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        motionHandler.post { if (!destroyed) { failed = false; bind(); onStateChanged?.invoke() } }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        motionHandler.post { if (!destroyed) connection.onServiceDisconnected(null) }
    }

    // Stores the state-change callback and registers the Shizuku permission result listener
    // so bind() is called automatically if the user grants permission while the app is open.
    fun init(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
    }

    // Unregisters the permission listener and unbinds (and destroys) the UserService.
    fun destroy() {
        destroyed = true
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        connectionScope.cancel()
        unbind()
    }

    // Returns true if the Shizuku daemon is running and its binder is reachable.
    fun hasShizuku(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // Returns true if Shizuku is running AND this app has been granted shell-uid permission.
    fun hasPermission(): Boolean =
        runCatching { hasShizuku() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    // Opens Shizuku's permission dialog if the daemon is running but permission hasn't been granted.
    fun requestPermission() {
        if (hasShizuku() && !hasPermission()) Shizuku.requestPermission(1001)
    }

    // Asks Shizuku to start MouseService as a shell-uid UserService and bind to it via
    // the ServiceConnection. No-op if already connected or permission not granted.
    fun bind() {
        if (destroyed || binding || failed || isConnected || !hasPermission()) return
        binding = true
        connectionMessage = "Connecting to Shizuku…"
        motionHandler.postDelayed(bindTimeout, 10_000)
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }.onFailure {
            binding = false; failed = true
            motionHandler.removeCallbacks(bindTimeout)
            connectionMessage = "Could not start the mouse service. Retry connection."
        }
    }

    // Sends destroy + unbind to Shizuku and clears the local service reference.
    fun unbind() {
        binding = false
        healthJob?.cancel()
        motionHandler.removeCallbacks(bindTimeout)
        displayJob?.cancel()
        displayBinding.invalidate()
        resetPendingInput()
        // Remove the local cached callback before killing its remote service. Otherwise a
        // late death callback can clear a newly-added connection during rapid reopen.
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, false) }
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        isConnected = false
    }

    // Forwards display id and pixel dimensions to MouseService so it can target
    // cursor and key events at the correct display.
    fun setDisplay(displayId: Int, width: Int, height: Int) {
        val target = MouseDisplayTarget(displayId, width, height)
        if (displayBinding.select(target)) {
            failed = false
            displayJob?.cancel()
            resetPendingInput()
        }
        if (!active || failed || displayBinding.isReady || displayJob?.isActive == true) return
        val connected = service ?: return
        val revision = displayBinding.revision
        displayJob = connectionScope.launch {
            var retryDelay = 250L
            var attempts = 0
            while (isActive && attempts++ < 8) {
                // Creation and association run in the service, never on the UI thread.
                val ready = runCatching {
                    connected.setDisplay(target.id, target.width, target.height)
                    withContext(Dispatchers.IO) {
                        connected.isDisplayReady(target.id, target.width, target.height)
                    }
                }.getOrDefault(false)
                if (ready && service === connected && displayBinding.markReady(revision)) {
                    connectionMessage = "Mouse ready"
                    onStateChanged?.invoke()
                    return@launch
                }
                // Display/input registration can lag the hotplug notification. Back off
                // while waiting, and cancel immediately when the target disappears.
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(2000L)
            }
            if (isActive && service === connected && revision == displayBinding.revision) {
                failed = true
                val detail = withContext(Dispatchers.IO) { runCatching { connected.lastError }.getOrNull() }
                connectionMessage = detail?.takeIf { it.isNotBlank() }
                    ?: "Mouse could not attach to this display. Check extended-display mode, then retry."
                onStateChanged?.invoke()
            }
        }
    }

    fun clearDisplay() {
        val changed = displayBinding.select(null)
        if (changed) failed = false
        displayJob?.cancel()
        displayJob = null
        resetPendingInput()
        if (changed) runCatching { service?.clearDisplay() }
    }

    fun setPointerHidden(hidden: Boolean) {
        if (pointerHidden == hidden) return
        pointerHidden = hidden
        resetPendingInput()
        runCatching { service?.setPointerHidden(hidden) }
        // Visibility does not change the target or readiness. The next movement can be
        // sent immediately, ordered after this call on the same Binder.
    }

    fun setNotificationShadeBlocked(blocked: Boolean) {
        if (notificationShadeBlocked == blocked) return
        notificationShadeBlocked = blocked
        runCatching { service?.setNotificationShadeBlocked(blocked) }
    }

    fun showPhoneOverview() { runCatching { service?.showPhoneOverview() } }

    fun showModeFeedback(badge: android.graphics.Bitmap) { runCatching { service?.showModeFeedback(badge) } }
    fun dismissModeFeedback() { runCatching { service?.dismissModeFeedback() } }
    fun setDragReady(ready: Boolean) { runCatching { service?.setDragReady(ready) } }

    // Rate-limits IPC to ~60 Hz but accumulates deltas so no motion is lost between
    // allowed frames. Without accumulation, high-Hz touch input (90/120 Hz) would
    // silently drop half the events, causing jitter on slow/precise movements.
    fun moveMouse(dx: Float, dy: Float) {
        if (isDisplayReady) movement.add(dx, dy)
    }

    fun flushMovement() = movement.flush()

    fun cancelPendingInput() = resetPendingInput()

    private fun resetPendingInput() {
        movement.reset()
        scrolling.reset()
    }

    // Single left-click at the current cursor position.
    fun click(x: Float, y: Float) {
        if (!isDisplayReady) return
        flushMovement()
        flushScroll()
        runCatching { service?.click(x, y, MotionEvent.BUTTON_PRIMARY) }
    }

    // Single right-click (long-press gesture on touchscreens) at the current cursor position.
    fun rightClick(x: Float, y: Float) {
        if (!isDisplayReady) return
        flushMovement()
        flushScroll()
        runCatching { service?.click(x, y, MotionEvent.BUTTON_SECONDARY) }
    }

    // Forwards scroll deltas (finger pixels) to MouseService for wheel conversion, paced like
    // moveMouse and smoothed: each interval sends SCROLL_SMOOTHING of the distance waiting and
    // carries the rest forward. Fingertip jitter on a slow scroll is larger than the motion
    // itself, and unsmoothed it reaches the app as a stutter of tiny forward and backward
    // wheel steps. Nothing is lost — the remainder goes out on later intervals, and
    // flushScroll() sends what is left when the gesture ends.
    fun scroll(dx: Float, dy: Float) { if (isDisplayReady) scrolling.add(dx, dy) }
    fun finishScroll() = scrolling.finish()
    fun flushScroll() = scrolling.flush()

    // Injects an Android keycode (e.g. KEYCODE_BACK) targeted at the glasses display.
    fun pressKey(linuxKeyCode: Int) {
        runCatching { service?.pressKey(linuxKeyCode) }
    }

    fun enableSettingsOverlay(): Boolean =
        runCatching { service?.enableSettingsOverlay() == true }.getOrDefault(false)

    fun mediaState(): MediaState {
        val bundle = runCatching { service?.mediaState }.getOrNull() ?: return MediaState()
        return MediaState(
            playing = when (bundle.getInt("playing", -1)) { 1 -> true; 0 -> false; else -> null },
            muted = if (bundle.containsKey("muted")) bundle.getBoolean("muted") else null,
            volume = bundle.getInt("volume"), maxVolume = bundle.getInt("maxVolume"),
        )
    }

    fun sendMediaControl(key: Int): Boolean =
        runCatching { isDisplayReady && service?.sendMediaControl(key) == true }.getOrDefault(false)

    // Converts a text string to KeyEvents and injects them to the glasses display.
    fun typeText(text: String) {
        runCatching { service?.typeText(text) }
    }

    // Sets the system font_scale via shell `settings put`, clamped 0.85–1.5.
    fun setFontScale(scale: Float) {
        runCatching { service?.setFontScale(scale) }
    }

    // Presses BTN_LEFT without releasing — call moveMouse while held for click-drag selection.
    fun mouseDown() { flushMovement(); flushScroll(); runCatching { service?.mouseDown() } }
    // Releases BTN_LEFT pressed by mouseDown().
    fun mouseUp()   { flushMovement(); runCatching { service?.mouseUp() } }

    // Injects Ctrl+keycode (e.g. KEYCODE_C for Copy) without moving the cursor.
    fun pressKeyWithCtrl(keycode: Int) { runCatching { service?.pressKeyWithCtrl(keycode) } }

    fun toggleMoonlightCapture() { runCatching { service?.toggleMoonlightCapture() } }

    // Injects a Ctrl+scroll MotionEvent for content-level zoom in Chrome/WebView apps.
    fun ctrlScroll(amount: Float) { runCatching { service?.ctrlScroll(amount) } }

    // Sets the per-display IME policy (0 = local, 1 = fallback → keyboard on phone, 2 = hide).
    // Returns false if the service isn't bound or the wm command isn't supported on this build.
    fun setImePolicy(displayId: Int, policy: Int): Boolean =
        runCatching { service?.setImePolicy(displayId, policy) ?: false }.getOrDefault(false)

    fun retry() {
        failed = false
        displayJob?.cancel()
        displayJob = null
        displayBinding.invalidate()
        resetPendingInput()
        val connected = service
        if (connected != null && connected.asBinder().isBinderAlive) {
            // Recreate the mouse on the live service. Immediately destroying and rebinding
            // can race Shizuku's removal of its old service record.
            runCatching { connected.clearDisplay() }
            connectionMessage = "Preparing mouse for the display…"
        } else {
            unbind()
            bind()
        }
    }

    private fun input(action: (IMouseService) -> Unit) {
        val connected = service ?: return
        runCatching { action(connected) }.onFailure {
            if (service === connected) {
                displayBinding.invalidate(); resetPendingInput(); failed = true
                connectionMessage = "Input connection failed. Retry connection."
                motionHandler.post { onStateChanged?.invoke() }
            }
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) clearDisplay()
        startHealthChecks()
        onStateChanged?.invoke()
    }
    private fun startHealthChecks() {
        healthJob?.cancel()
        if (!active || !isConnected || destroyed) return
        healthJob = connectionScope.launch {
            while (isActive) {
                delay(5000)
                val target = displayBinding.target ?: continue
                if (!displayBinding.isReady) continue
                val connected = service ?: continue
                val revision = displayBinding.revision
                val healthy = withContext(Dispatchers.IO) {
                    runCatching { connected.isDisplayReady(target.id, target.width, target.height) }.getOrDefault(false)
                }
                if (!healthy && service === connected && revision == displayBinding.revision) {
                    displayBinding.invalidate(); resetPendingInput(); failed = true
                    connectionMessage = withContext(Dispatchers.IO) {
                        runCatching { connected.lastError }.getOrNull()
                    }?.takeIf { it.isNotBlank() } ?: "Mouse lost its display connection. Retry connection."
                    onStateChanged?.invoke()
                }
            }
        }
    }
}
