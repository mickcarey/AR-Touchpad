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

    private var service: IMouseService? = null
    private var pointerHidden = false
    private var onStateChanged: (() -> Unit)? = null
    private val motionHandler = Handler(Looper.getMainLooper())
    private val movement = MotionBatcher(
        now = SystemClock::uptimeMillis,
        schedule = { task, delay -> motionHandler.postDelayed(task, delay); Unit },
        cancel = { motionHandler.removeCallbacks(it) },
        send = { dx, dy -> runCatching { service?.moveMouse(dx, dy) }; Unit },
    )
    private val displayBinding = DisplayBinding()
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var displayJob: Job? = null
    val isDisplayReady get() = displayBinding.isReady
    private var lastScrollMs = 0L
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.pgratz.artouchpad", MouseService::class.java.name)
    )
        .processNameSuffix("mouse")
        .daemon(false)
        .version(30)  // hold native colour until drag-ready ends; brief upward size pulse

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // Shizuku can report an already-connected service again. Keep its motion
            // state and display association instead of resetting a live pointer.
            if (isConnected && service?.asBinder() == binder) return
            displayJob?.cancel()
            displayBinding.invalidate()
            resetPendingInput()
            service = IMouseService.Stub.asInterface(binder)
            runCatching { service?.setPointerHidden(pointerHidden) }
            isConnected = service != null
            onStateChanged?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            displayJob?.cancel()
            displayBinding.invalidate()
            resetPendingInput()
            service = null
            isConnected = false
            onStateChanged?.invoke()
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind()
        onStateChanged?.invoke()
    }

    // Stores the state-change callback and registers the Shizuku permission result listener
    // so bind() is called automatically if the user grants permission while the app is open.
    fun init(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    // Unregisters the permission listener and unbinds (and destroys) the UserService.
    fun destroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        connectionScope.cancel()
        unbind()
    }

    // Returns true if the Shizuku daemon is running and its binder is reachable.
    fun hasShizuku(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // Returns true if Shizuku is running AND this app has been granted shell-uid permission.
    fun hasPermission(): Boolean =
        hasShizuku() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    // Opens Shizuku's permission dialog if the daemon is running but permission hasn't been granted.
    fun requestPermission() {
        if (hasShizuku() && !hasPermission()) Shizuku.requestPermission(1001)
    }

    // Asks Shizuku to start MouseService as a shell-uid UserService and bind to it via
    // the ServiceConnection. No-op if already connected or permission not granted.
    fun bind() {
        if (hasPermission() && !isConnected) {
            Shizuku.bindUserService(userServiceArgs, connection)
        }
    }

    // Sends destroy + unbind to Shizuku and clears the local service reference.
    fun unbind() {
        displayJob?.cancel()
        displayBinding.invalidate()
        resetPendingInput()
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        isConnected = false
    }

    // Forwards display id and pixel dimensions to MouseService so it can target
    // cursor and key events at the correct display.
    fun setDisplay(displayId: Int, width: Int, height: Int) {
        val target = MouseDisplayTarget(displayId, width, height)
        if (displayBinding.select(target)) {
            displayJob?.cancel()
            resetPendingInput()
        }
        if (displayBinding.isReady || displayJob?.isActive == true) return
        val connected = service ?: return
        val revision = displayBinding.revision
        displayJob = connectionScope.launch {
            var retryDelay = 250L
            while (isActive) {
                // Creation and association run in the service, never on the UI thread.
                val ready = runCatching {
                    connected.setDisplay(target.id, target.width, target.height)
                    withContext(Dispatchers.IO) {
                        connected.isDisplayReady(target.id, target.width, target.height)
                    }
                }.getOrDefault(false)
                if (ready && service === connected && displayBinding.markReady(revision)) {
                    onStateChanged?.invoke()
                    return@launch
                }
                // Display/input registration can lag the hotplug notification. Back off
                // while waiting, and cancel immediately when the target disappears.
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(2000L)
            }
        }
    }

    fun clearDisplay() {
        val changed = displayBinding.select(null)
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
        pendingScrollX = 0f
        pendingScrollY = 0f
        lastScrollMs = 0L
    }

    // Single left-click at the current cursor position.
    fun click(x: Float, y: Float) {
        if (!isDisplayReady) return
        flushMovement()
        runCatching { service?.click(x, y, MotionEvent.BUTTON_PRIMARY) }
    }

    // Single right-click (long-press gesture on touchscreens) at the current cursor position.
    fun rightClick(x: Float, y: Float) {
        if (!isDisplayReady) return
        flushMovement()
        runCatching { service?.click(x, y, MotionEvent.BUTTON_SECONDARY) }
    }

    // Forwards scroll deltas (finger pixels) to MouseService for wheel conversion, paced like
    // moveMouse and smoothed: each interval sends SCROLL_SMOOTHING of the distance waiting and
    // carries the rest forward. Fingertip jitter on a slow scroll is larger than the motion
    // itself, and unsmoothed it reaches the app as a stutter of tiny forward and backward
    // wheel steps. Nothing is lost — the remainder goes out on later intervals, and
    // flushScroll() sends what is left when the gesture ends.
    fun scroll(dx: Float, dy: Float) {
        pendingScrollX += dx
        pendingScrollY += dy
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollMs < 16L) return
        lastScrollMs = now
        val sendX = pendingScrollX * SCROLL_SMOOTHING
        val sendY = pendingScrollY * SCROLL_SMOOTHING
        pendingScrollX -= sendX; pendingScrollY -= sendY
        runCatching { service?.scroll(sendX, sendY) }
    }

    // Sends whatever scroll distance is still being held back.
    fun flushScroll() {
        if (pendingScrollX == 0f && pendingScrollY == 0f) return
        val sendX = pendingScrollX; val sendY = pendingScrollY
        pendingScrollX = 0f; pendingScrollY = 0f
        runCatching { service?.scroll(sendX, sendY) }
    }

    // Injects an Android keycode (e.g. KEYCODE_BACK) targeted at the glasses display.
    fun pressKey(linuxKeyCode: Int) {
        runCatching { service?.pressKey(linuxKeyCode) }
    }

    // Converts a text string to KeyEvents and injects them to the glasses display.
    fun typeText(text: String) {
        runCatching { service?.typeText(text) }
    }

    // Sets the system font_scale via shell `settings put`, clamped 0.85–1.5.
    fun setFontScale(scale: Float) {
        runCatching { service?.setFontScale(scale) }
    }

    // Presses BTN_LEFT without releasing — call moveMouse while held for click-drag selection.
    fun mouseDown() { flushMovement(); runCatching { service?.mouseDown() } }
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

    private companion object {
        // Share of the pending scroll distance sent per ~16 ms interval; the rest is carried.
        const val SCROLL_SMOOTHING = 0.4f
    }
}
