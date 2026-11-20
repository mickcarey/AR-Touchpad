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

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Shizuku UserService — runs as shell uid.
 *
 * Creates a real virtual mouse via /dev/uinput using JNI (the only reliable
 * way to call ioctl with a value argument on Android 16, where Os.ioctl was
 * stripped down to ioctlInetAddress and ioctlInt(fd, req) — no generic variant).
 *
 * Shell uid is in the `input` group and has rw access to /dev/uinput, so no
 * additional permissions are needed beyond what Shizuku already grants.
 */
class MouseService(private val context: android.content.Context) : IMouseService.Stub() {

    private val mediaControls by lazy { MediaControls(context) }

    // StatusBarManager owns a Binder token scoped to this service process. Android also
    // releases its flags on process death, including an unexpected Shizuku shutdown.
    private val statusBar by lazy { context.getSystemService("statusbar") }

    override fun setNotificationShadeBlocked(blocked: Boolean) = asShell {
        runCatching {
            val manager = requireNotNull(statusBar)
            val type = manager.javaClass
            val flags = if (blocked) type.getField("DISABLE2_NOTIFICATION_SHADE").getInt(null) or
                type.getField("DISABLE2_QUICK_SETTINGS").getInt(null) else 0
            type.getMethod("disable2", Int::class.javaPrimitiveType).invoke(manager, flags)
        }.onFailure { Log.w(TAG, "Could not update the phone notification shade", it) }
        Unit
    }

    private fun <T> asShell(block: () -> T): T {
        val identity = android.os.Binder.clearCallingIdentity()
        return try { block() } finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    override fun getMediaState(): android.os.Bundle = asShell {
        runCatching { mediaControls.snapshot() }.onFailure { Log.w(TAG, "Media state unavailable", it) }
            .getOrElse { android.os.Bundle() }
    }

    override fun sendMediaControl(keycode: Int): Boolean = asShell {
        if (pointerHidden || readyTarget == null) false
        else runCatching { mediaControls.send(keycode) }
            .onFailure { Log.w(TAG, "Media control failed", it) }.getOrDefault(false)
    }

    override fun enableSettingsOverlay(): Boolean = asShell {
        runCatching {
            val packageName = BuildConfig.APPLICATION_ID
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
            android.app.AppOpsManager::class.java.getMethod("setMode", String::class.java,
                Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(appOps, android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid,
                    packageName, android.app.AppOpsManager.MODE_ALLOWED)
            true
        }.onFailure { Log.w(TAG, "Settings overlay unavailable", it) }.getOrDefault(false)
    }

    @Volatile private var lastFailure = ""
    override fun getLastError(): String = lastFailure

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val modeFeedback = ModeFeedbackOverlay(mainHandler)
    private val cursorFeedback = CursorFeedback()
    private val cursorVisibility by lazy {
        runCatching {
            val method = imgClass!!.getMethod("setPointerIcon", android.view.PointerIcon::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                android.os.IBinder::class.java)
            CursorVisibility(context, mainHandler) { icon, display, device, token ->
                method.invoke(imgInstance, icon, display, device, 0, token) == true
            }
        }.onFailure { Log.w(TAG, "Icon visibility unsupported; using device removal", it) }.getOrNull()
    }

    private fun updateCursorVisibility() {
        val target = displayId
        val device = findUinputDevice()?.id ?: -1
        mainHandler.post {
            if (target == displayId) runCatching {
                cursorVisibility?.setHidden(pointerHidden, target, device)
            }.onFailure { Log.w(TAG, "Cursor visibility unavailable", it) }
        }
    }

    override fun showModeFeedback(badge: android.graphics.Bitmap) {
        val target = displayId
        mainHandler.post {
            try {
                if (target == displayId && target >= 0) {
                    runCatching { modeFeedback.show(target, displayWidth, displayHeight, badge) }
                    .onFailure { Log.w(TAG, "Mode feedback unavailable", it) }
                }
            } finally { badge.recycle() }
        }
    }

    override fun dismissModeFeedback() {
        cursorFeedback.cancel()
        mainHandler.post { modeFeedback.dismiss() }
    }

    override fun setDragReady(ready: Boolean) {
        // Native styling leaves icon selection entirely with the hovered app.
        if (ready && !pointerHidden && readyTarget != null) cursorFeedback.pulse()
        else cursorFeedback.cancel()
    }

    private var displayId = android.view.Display.INVALID_DISPLAY
    @Volatile private var readyTarget: MouseDisplayTarget? = null
    private var displayWidth = 1920
    private var displayHeight = 1080
    private var zoomUnits = 0f
    private var zoomDetentUnits = 0

    private var uinputReady = false
    @Volatile private var pointerHidden = false
    // Cached descriptor string for our uinput device; set after InputReader registers it.
    // Used by associateDeviceToDisplay() to pin the cursor to the external display.
    private var uinputDescriptor: String? = null

    // Sub-pixel accumulators: carry fractional remainders between calls so that
    // slow movements (e.g. 0.4 px/frame) accumulate cleanly instead of truncating
    // to zero every frame and producing sudden jumps.
    private var accumX = 0f
    private var accumY = 0f
    // Scroll accumulators, in high-resolution wheel units (120 per detent): the fractional
    // remainder not yet emitted as REL_*WHEEL_HI_RES, and the whole units emitted since the
    // last legacy REL_WHEEL/REL_HWHEEL detent.
    private var accumScrollX = 0f
    private var accumScrollY = 0f
    private var detentUnitsX = 0
    private var detentUnitsY = 0

    // Reflection handles for display-targeted key injection and device-display association.
    // injectInputEvent(event, mode) on InputManagerGlobal respects the displayId
    // embedded in the event, routing the key to the focused window on that display.
    private val imgClass by lazy {
        runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
    }
    private val imgInstance by lazy {
        imgClass?.getMethod("getInstance")?.invoke(null)
    }
    private val imgInjectEvent by lazy {
        imgClass?.getMethod("injectInputEvent",
            android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayIdMethod by lazy {
        runCatching {
            android.view.InputEvent::class.java
                .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.getOrNull()
    }
    private val imgGetDeviceIds by lazy {
        runCatching { imgClass?.getMethod("getInputDeviceIds") }.getOrNull()
    }
    private val imgGetDevice by lazy {
        runCatching {
            imgClass?.getMethod("getInputDevice", Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val getAssociatedDisplayId by lazy {
        runCatching { InputDevice::class.java.getMethod("getAssociatedDisplayId") }.getOrNull()
    }
    // Current AOSP association API (replaced setInputDeviceDisplayAssociation at some point
    // before Android 17): keyed by the display's uniqueId string rather than its integer id.
    private val imgAddUniqueIdAssoc by lazy {
        runCatching {
            imgClass?.getMethod("addUniqueIdAssociationByDescriptor",
                String::class.java, String::class.java)
        }.getOrNull()
    }
    // Legacy association API, kept as a fallback for pre-Android-15 builds where the
    // uniqueId-based method above doesn't exist yet.
    private val imgSetDisplayAssoc by lazy {
        runCatching {
            imgClass?.getMethod("setInputDeviceDisplayAssociation",
                String::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    // Reflection handles for resolving a display's uniqueId string (e.g. "local:462...") from
    // its integer id, via the same hidden-global pattern as InputManagerGlobal above. Needed
    // because addUniqueIdAssociationByDescriptor takes a uniqueId, not an integer displayId.
    private val dmgClass by lazy {
        runCatching { Class.forName("android.hardware.display.DisplayManagerGlobal") }.getOrNull()
    }
    private val dmgInstance by lazy {
        runCatching { dmgClass?.getMethod("getInstance")?.invoke(null) }.getOrNull()
    }
    private val dmgGetDisplayInfo by lazy {
        runCatching { dmgClass?.getMethod("getDisplayInfo", Int::class.javaPrimitiveType) }.getOrNull()
    }

    // Reflection handles for IWindowManager.setDisplayImePolicy — controls which display
    // shows the IME for fields focused on a given display. Shell uid holds the required
    // INTERNAL_SYSTEM_WINDOW permission, so the binder call passes the WMS check.
    private val iwmInstance by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as android.os.IBinder
            Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }
    private val iwmSetImePolicy by lazy {
        runCatching {
            iwmInstance?.javaClass?.getMethod("setDisplayImePolicy",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    // Create the mouse only after an external display exists. A mouse registered
    // before hotplug can retain the phone's pointer/display state in InputReader.

    // Opens /dev/uinput via JNI, declares mouse capabilities (REL_X/Y, wheel incl. hi-res, buttons),
    // writes the device name, and issues UI_DEV_CREATE. Waits 400 ms for InputReader to
    // enumerate the new device before marking uinputReady = true.
    private fun initUinput() {
        try {
            val fd = UinputNative.nOpen()
            check(fd >= 0) { "Cannot open virtual mouse. Check Shizuku permission and device support." }

            fun ioctl(req: Int, value: Int) {
                val r = UinputNative.nIoctl(req, value)
                check(r >= 0) { "Virtual mouse configuration failed (0x${req.toString(16)})." }
            }

            ioctl(UI_SET_EVBIT,  EV_SYN)
            ioctl(UI_SET_EVBIT,  EV_KEY)
            ioctl(UI_SET_EVBIT,  EV_REL)
            ioctl(UI_SET_RELBIT, REL_X)
            ioctl(UI_SET_RELBIT, REL_Y)
            ioctl(UI_SET_RELBIT, REL_WHEEL)
            ioctl(UI_SET_RELBIT, REL_HWHEEL)
            ioctl(UI_SET_RELBIT, REL_WHEEL_HI_RES)
            ioctl(UI_SET_RELBIT, REL_HWHEEL_HI_RES)
            ioctl(UI_SET_KEYBIT, BTN_LEFT)
            ioctl(UI_SET_KEYBIT, BTN_RIGHT)
            ioctl(UI_SET_KEYBIT, BTN_MIDDLE)
            ioctl(UI_SET_KEYBIT, KEY_LEFTCTRL)
            ioctl(UI_SET_KEYBIT, KEY_BACK)
            ioctl(UI_SET_KEYBIT, KEY_HOME)
            ioctl(UI_SET_KEYBIT, KEY_APPSWITCH)

            val n = UinputNative.nWriteDevInfo(DEVICE_NAME)
            check(n > 0) { "Could not register the virtual mouse." }

            ioctl(UI_DEV_CREATE, 0)

            Thread.sleep(400) // give InputReader time to register the device
            uinputReady = true
            uinputDescriptor = findUinputDescriptor()
            Log.i(TAG, "uinput device ready, descriptor=$uinputDescriptor")
        } catch (e: Throwable) {
            lastFailure = e.message ?: "Virtual mouse setup failed."
            Log.e(TAG, "initUinput failed", e)
        } finally {
            if (!uinputReady) runCatching { UinputNative.nClose() }
        }
    }

    // Scans all registered input devices via InputManagerGlobal and returns the descriptor
    // of the one named "AR Touchpad Mouse". Returns null if not found or reflection fails.
    private fun findUinputDescriptor(): String? = findUinputDevice()?.descriptor

    private fun findUinputDevice(): InputDevice? = runCatching {
        val instance = imgInstance ?: return@runCatching null
        val ids = imgGetDeviceIds?.invoke(instance) as? IntArray ?: return@runCatching null
        val getDevice = imgGetDevice ?: return@runCatching null
        ids.toList().mapNotNull { id ->
            (getDevice.invoke(instance, id) as? android.view.InputDevice)
                ?.takeIf { it.name == DEVICE_NAME }
        }.firstOrNull()
    }.getOrNull()

    // Resolves the uniqueId string DisplayManagerGlobal uses to identify a display
    // (e.g. "local:<display-id>"), required by addUniqueIdAssociationByDescriptor.
    private fun getDisplayUniqueId(targetDisplayId: Int): String? = runCatching {
        val instance = dmgInstance ?: return@runCatching null
        val getInfo = dmgGetDisplayInfo ?: return@runCatching null
        val info = getInfo.invoke(instance, targetDisplayId) ?: return@runCatching null
        info.javaClass.getField("uniqueId").get(info) as? String
    }.getOrNull()

    // Tells Android's InputReader to route this uinput device's cursor to targetDisplayId.
    // Tries, in order: the current AOSP API (addUniqueIdAssociationByDescriptor, keyed by the
    // display's uniqueId string), the same method via the raw IInputManager binder (mIm field)
    // if not exposed directly on InputManagerGlobal, and finally the legacy integer-displayId
    // method (setInputDeviceDisplayAssociation) for any pre-Android-15 device still running
    // this app. The uniqueId-based API replaced the legacy one at some point before Android 17
    // — on builds where only the legacy name resolved, association silently no-opped and the
    // uinput device was never actually pinned to the external display, so REL_X/REL_Y motion
    // moved a cursor on the wrong (invisible) display while BTN_LEFT clicks, which are
    // dispatched to the focused window rather than a device-associated display, still worked.
    private fun associateDeviceToDisplay(descriptor: String, targetDisplayId: Int): Boolean =
        runCatching {
            val instance = imgInstance ?: return@runCatching false
            val uniqueId = getDisplayUniqueId(targetDisplayId)

            if (uniqueId != null) {
                val direct = imgAddUniqueIdAssoc
                if (direct != null) {
                    direct.invoke(instance, descriptor, uniqueId)
                    Log.i(TAG, "associated uinput device to display $targetDisplayId ($uniqueId)")
                    return@runCatching true
                }
                val mImField = imgClass?.getDeclaredField("mIm")?.also { it.isAccessible = true }
                val iim = mImField?.get(instance)
                val viaBinder = iim?.javaClass?.let {
                    runCatching {
                        it.getMethod("addUniqueIdAssociationByDescriptor",
                            String::class.java, String::class.java)
                    }.getOrNull()
                }
                if (iim != null && viaBinder != null) {
                    viaBinder.invoke(iim, descriptor, uniqueId)
                    Log.i(TAG, "associated uinput device (via mIm) to display $targetDisplayId ($uniqueId)")
                    return@runCatching true
                }
            }

            val legacy = imgSetDisplayAssoc
            if (legacy != null) {
                legacy.invoke(instance, descriptor, targetDisplayId)
                Log.i(TAG, "associated uinput device to display $targetDisplayId (legacy API)")
                return@runCatching true
            }

            Log.w(TAG, "no display-association API available (uniqueId=$uniqueId)")
            false
        }.getOrElse { Log.w(TAG, "associateDeviceToDisplay failed: $it"); false }

    // Writes a single struct input_event{type, code, value} to the open uinput fd via JNI.
    private fun ev(type: Int, code: Int, value: Int): Boolean {
        if (!uinputReady) return false
        if (UinputNative.nWriteEvent(type, code, value) > 0) return true
        lastFailure = "Virtual mouse stopped accepting input. Retry connection."
        readyTarget = null
        uinputReady = false
        cursorFeedback.cancel()
        UinputNative.nClose()
        return false
    }
    // Flushes all buffered events to the input dispatcher with an EV_SYN/SYN_REPORT marker.
    private fun sync() = ev(EV_SYN, SYN_REPORT, 0)

    // Stores the target display id and pixel dimensions; resets cursor to center and clears
    // accumulators. Associates the uinput device to the target display so that REL_X/Y events
    // move the cursor on the glasses screen instead of the phone screen. Sends a 1-px nudge
    // to wake the OS cursor on the new display.
    @Synchronized override fun setDisplay(id: Int, width: Int, height: Int) {
        val target = MouseDisplayTarget(id, width, height)
        if (isDisplayReady(id, width, height)) return
        readyTarget = null
        if (id == android.view.Display.DEFAULT_DISPLAY || id < 0 || width <= 0 || height <= 0) return
        if (getDisplayUniqueId(id) == null) return // display is still being registered or was removed
        if (displayId != id || displayWidth != width || displayHeight != height) clearDisplay(null)
        displayId = id
        displayWidth = width
        displayHeight = height
        zoomUnits = 0f; zoomDetentUnits = 0
        accumX = 0f
        accumY = 0f
        accumScrollX = 0f
        accumScrollY = 0f
        detentUnitsX = 0
        detentUnitsY = 0

        if (pointerHidden && cursorVisibility == null) {
            // The display is usable for settings/gestures, but has no pointer while locked.
            readyTarget = target
            return
        }

        // Pin the uinput device to this display so cursor movement lands on the glasses screen.
        // Without this, Android may default the virtual mouse to the phone's display on some setups.
        if (!uinputReady) initUinput()
        if (!uinputReady) return
        val descriptor = uinputDescriptor ?: findUinputDescriptor()?.also { uinputDescriptor = it }
        if (descriptor == null || !associateDeviceToDisplay(descriptor, id)) {
            lastFailure = "Could not route the mouse to this display. Check extended-display mode."
            return
        }

        // Nudge the pointer to wake the cursor.
        if (uinputReady) {
            ev(EV_REL, REL_X, 1); ev(EV_REL, REL_Y, 1); sync()
            Thread.sleep(50)
            ev(EV_REL, REL_X, -1); ev(EV_REL, REL_Y, -1); sync()
        }
        if (!uinputReady) return
        lastFailure = ""
        readyTarget = target
        updateCursorVisibility()
        Log.i(TAG, "setDisplay id=$id ${width}x${height} uinputReady=$uinputReady")
    }

    @Synchronized override fun isDisplayReady(id: Int, width: Int, height: Int): Boolean {
        if (readyTarget != MouseDisplayTarget(id, width, height)) return false
        if (pointerHidden && cursorVisibility == null) return true
        val device = findUinputDevice() ?: return false
        // InputReader applies the association asynchronously. A successful Binder call
        // alone does not mean its cursor mapper has switched to the new display yet.
        val method = getAssociatedDisplayId ?: return true // legacy API fallback
        return runCatching { method.invoke(device) == id }.getOrDefault(false)
    }

    @Synchronized override fun setPointerHidden(hidden: Boolean) {
        if (pointerHidden == hidden) return
        pointerHidden = hidden
        if (hidden) cursorFeedback.cancel()
        if (cursorVisibility != null) {
            // Keep the same registered device and display binding. Settings can accept the
            // very next motion event; there is no recreation, registration sleep or retry.
            if (hidden) mouseUp()
            updateCursorVisibility()
            return
        }
        if (hidden) {
            // Releasing our own uinput device removes its system cursor without clicking,
            // moving it off-screen, changing another mouse, or capturing app focus.
            if (uinputReady) {
                ev(EV_KEY, BTN_LEFT, 0)
                ev(EV_KEY, BTN_RIGHT, 0)
                sync()
            }
            UinputNative.nClose()
            uinputReady = false
            uinputDescriptor = null
        } else if (displayId >= 0) {
            // InputManager may briefly still list the just-removed device. Force creation
            // on the compatibility path instead of accepting that stale readiness result.
            readyTarget = null
            setDisplay(displayId, displayWidth, displayHeight)
        }
    }

    @Synchronized override fun clearDisplay(completion: android.os.ResultReceiver?) {
        imePolicies.restoreAll()
        dismissModeFeedback()
        mainHandler.post { cursorVisibility?.restore() }
        readyTarget = null
        if (uinputReady) {
            ev(EV_KEY, BTN_LEFT, 0)
            ev(EV_KEY, BTN_RIGHT, 0)
            sync()
        }
        UinputNative.nClose()
        uinputReady = false
        uinputDescriptor = null
        displayId = android.view.Display.INVALID_DISPLAY
        accumX = 0f; accumY = 0f
        accumScrollX = 0f; accumScrollY = 0f
        detentUnitsX = 0; detentUnitsY = 0
        Log.i(TAG, "external display detached; virtual mouse released")
        completion?.send(android.app.Activity.RESULT_OK, null)
    }

    // Accumulates fractional deltas in accumX/Y; only emits REL_X/REL_Y events for the
    // whole-pixel portion, carrying the remainder forward. Also clamps the tracked cursor
    // position to the display bounds so the ViewModel overlay stays in sync.
    @Synchronized override fun moveMouse(dx: Float, dy: Float) {
        if (pointerHidden || !uinputReady || readyTarget == null) return
        accumX += dx
        accumY += dy
        val idx = accumX.toInt()
        val idy = accumY.toInt()
        if (idx == 0 && idy == 0) return
        accumX -= idx
        accumY -= idy
        ev(EV_REL, REL_X, idx)
        ev(EV_REL, REL_Y, idy)
        sync()
    }

    // Separate SYN_REPORT frames preserve down/up without holding up later motion.
    // x/y are accepted for interface symmetry but cursor position is already tracked by moveMouse.
    @Synchronized override fun click(x: Float, y: Float, button: Int) {
        if (pointerHidden || !uinputReady || readyTarget == null) return
        val btn = if (button == MotionEvent.BUTTON_SECONDARY) BTN_RIGHT else BTN_LEFT
        ev(EV_KEY, btn, 1); sync()
        ev(EV_KEY, btn, 0); sync()
        Log.d(TAG, "click btn=0x${btn.toString(16)} displayId=$displayId")
    }

    // Input: Android keycode (e.g. KeyEvent.KEYCODE_BACK = 4).
    // Creates ACTION_DOWN + ACTION_UP KeyEvents, stamps each with the target displayId via
    // InputEvent.setDisplayId reflection, then calls InputManagerGlobal.injectInputEvent so
    // the key reaches the focused window on the glasses display rather than the phone.
    override fun pressKey(androidKeycode: Int) {
        if (pointerHidden || readyTarget == null) return
        injectChord(listOf(androidKeycode to 0))
    }

    override fun showPhoneOverview() {
        injectChord(listOf(KeyEvent.KEYCODE_APP_SWITCH to 0), android.view.Display.DEFAULT_DISPLAY)
    }

    private fun injectChord(keys: List<Pair<Int, Int>>, target: Int = displayId) {
        val instance = imgInstance ?: return
        val inject = imgInjectEvent ?: return
        val setDisp = setDisplayIdMethod ?: return
        val downTime = SystemClock.uptimeMillis()
        runCatching {
            sendKeyChord(keys) { key, down, meta ->
                val event = KeyEvent(downTime, SystemClock.uptimeMillis(),
                    if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key, 0, meta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                setDisp.invoke(event, target)
                check(inject.invoke(instance, event, 0) == true) { "Key injection rejected" }
            }
        }.onFailure { Log.e(TAG, "Key shortcut failed", it) }
    }

    // Input: a plain text string. Converts the full char array to a KeyEvent sequence via
    // KeyCharacterMap.VIRTUAL_KEYBOARD; falls back to per-character conversion for strings
    // that can't be mapped in one shot (e.g. mixed scripts). Delegates to injectKeyEvents.
    override fun typeText(text: String) {
        if (pointerHidden || readyTarget == null) return
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray())
        if (events != null) {
            injectKeyEvents(events)
        } else {
            for (ch in text) {
                val evs = kcm.getEvents(charArrayOf(ch)) ?: continue
                injectKeyEvents(evs)
            }
        }
        Log.d(TAG, "typeText length=${text.length} displayId=$displayId")
    }

    // For each KeyEvent in the array, constructs a new event carrying the same action/keycode/
    // meta state, stamps it with displayId via reflection, then calls injectInputEvent so it
    // lands on the focused window of the target display.
    private fun injectKeyEvents(events: Array<out KeyEvent>) {
        val instance = imgInstance ?: return
        val inject   = imgInjectEvent ?: return
        val setDisp  = setDisplayIdMethod ?: return
        for (ev in events) {
            val targeted = KeyEvent(ev.downTime, ev.eventTime, ev.action,
                                    ev.keyCode, ev.repeatCount, ev.metaState)
            setDisp.invoke(targeted, displayId)
            inject.invoke(instance, targeted, 0)
        }
    }

    // Input: (dx, dy) in finger pixels, as the distance the view should scroll right/down
    // (the ViewModel has already applied speed and the natural-scroll direction).
    // The two wheel axes have opposite sign conventions in the kernel — REL_WHEEL is positive
    // for "up", REL_HWHEEL is positive for "right" — so dy is negated and dx is not. Negating
    // both, as this once did, makes horizontal scrolling run backwards relative to vertical.
    // Emits high-resolution wheel events (REL_WHEEL_HI_RES / REL_HWHEEL_HI_RES, 120 units per
    // detent) so the content follows the fingers in 1/120-detent steps on both axes, instead
    // of jumping a whole wheel notch every SCROLL_PX_PER_DETENT of travel. As a real hi-res
    // mouse does, it also emits the legacy REL_WHEEL / REL_HWHEEL each time a full detent of
    // hi-res units has accumulated: InputReader uses the hi-res axis and ignores the legacy
    // one when the device declares both, and builds without hi-res support do the reverse.
    @Synchronized override fun scroll(dx: Float, dy: Float) {
        if (pointerHidden || !uinputReady || readyTarget == null) return
        accumScrollX += dx * HI_RES_UNITS_PER_PX
        accumScrollY += dy * HI_RES_UNITS_PER_PX
        val unitsX = accumScrollX.toInt()
        val unitsY = accumScrollY.toInt()
        if (unitsX == 0 && unitsY == 0) return
        accumScrollX -= unitsX
        accumScrollY -= unitsY
        if (unitsY != 0) {
            ev(EV_REL, REL_WHEEL_HI_RES, -unitsY)
            detentUnitsY -= unitsY
            val detents = detentUnitsY / HI_RES_UNITS_PER_DETENT
            if (detents != 0) {
                detentUnitsY -= detents * HI_RES_UNITS_PER_DETENT
                ev(EV_REL, REL_WHEEL, detents)
            }
        }
        if (unitsX != 0) {
            ev(EV_REL, REL_HWHEEL_HI_RES, unitsX)
            detentUnitsX += unitsX
            val detents = detentUnitsX / HI_RES_UNITS_PER_DETENT
            if (detents != 0) {
                detentUnitsX -= detents * HI_RES_UNITS_PER_DETENT
                ev(EV_REL, REL_HWHEEL, detents)
            }
        }
        sync()
    }

    // Input: desired font scale (clamped internally to 0.85–1.5).
    // Runs `settings put system font_scale <value>` as a subprocess; shell uid has permission
    // to write system settings, so no additional privileges are needed.
    override fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.5f)
        try {
            Runtime.getRuntime().exec(
                arrayOf("settings", "put", "system", "font_scale", "%.2f".format(clamped))
            ).waitFor()
            Log.d(TAG, "setFontScale $clamped")
        } catch (e: Exception) {
            Log.e(TAG, "setFontScale failed: $e")
        }
    }

    // Input: displayId and IME policy (0 = local, 1 = fallback, 2 = hide).
    // Calls IWindowManager.setDisplayImePolicy via reflection (there is no `wm` shell
    // subcommand for this). With policy 1 (fallback), a field focused on the target display
    // shows its IME on the default display (the phone) while the InputConnection stays with
    // the field — DeX-style typing. Returns false when unsupported on this build.
    private val imePolicies = ImePolicyOwner(
        read = { id ->
            val iwm = iwmInstance ?: error("Window manager unavailable")
            val getter = iwm.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
            getter.invoke(iwm, id) as Int
        },
        write = { id, policy ->
            val iwm = iwmInstance ?: error("Window manager unavailable")
            val setter = iwmSetImePolicy ?: error("Keyboard routing unsupported")
            setter.invoke(iwm, id, policy)
        },
    )

    @Synchronized override fun setImePolicy(displayId: Int, policy: Int): Boolean =
        displayId == this.displayId && readyTarget != null && imePolicies.apply(displayId, policy)

    // Moonlight tracks individual modifier key events, then toggles capture once all
    // modifiers are released. A Z event with meta flags alone does not trigger it.
    override fun toggleMoonlightCapture() {
        if (pointerHidden || readyTarget == null) return
        injectChord(listOf(
            KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
            KeyEvent.KEYCODE_Z to 0,
        ))
    }

    // Input: Android keycode (e.g. KEYCODE_C). Injects Ctrl+keycode via InputManagerGlobal
    // with META_CTRL_ON|META_CTRL_LEFT_ON so apps see a real Ctrl+key shortcut.
    // Used for Copy/Cut/Paste/SelectAll after a text selection without moving the cursor.
    override fun pressKeyWithCtrl(keycode: Int) {
        if (pointerHidden || readyTarget == null) return
        injectChord(listOf(KeyEvent.KEYCODE_CTRL_LEFT to
            (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON), keycode to 0))
    }

    // Use the actual mouse's wheel and modifier state. Android hit-tests at its real pointer
    // location, including acceleration and movement from other devices; no guessed coordinates.
    @Synchronized override fun ctrlScroll(amount: Float) {
        if (pointerHidden || !uinputReady || readyTarget == null || !amount.isFinite()) return
        zoomUnits += amount * HI_RES_UNITS_PER_DETENT
        val units = zoomUnits.toInt()
        if (units == 0) return
        zoomUnits -= units
        try {
            if (!ev(EV_KEY, KEY_LEFTCTRL, 1)) return
            sync()
            ev(EV_REL, REL_WHEEL_HI_RES, units)
            zoomDetentUnits += units
            val detents = zoomDetentUnits / HI_RES_UNITS_PER_DETENT
            if (detents != 0) {
                ev(EV_REL, REL_WHEEL, detents)
                zoomDetentUnits -= detents * HI_RES_UNITS_PER_DETENT
            }
            sync()
        } finally {
            ev(EV_KEY, KEY_LEFTCTRL, 0)
            sync()
        }
    }

    // Presses BTN_LEFT without releasing — the paired mouseUp() call ends the drag.
    // Used for text selection: button held while moveMouse moves the cursor.
    @Synchronized override fun mouseDown() {
        if (pointerHidden || !uinputReady || readyTarget == null) return
        ev(EV_KEY, BTN_LEFT, 1); sync()
        Log.d(TAG, "mouseDown displayId=$displayId")
    }

    // Releases BTN_LEFT previously pressed by mouseDown().
    @Synchronized override fun mouseUp() {
        if (!uinputReady) return
        ev(EV_KEY, BTN_LEFT, 0); sync()
        Log.d(TAG, "mouseUp displayId=$displayId")
    }

    // Closes the uinput file descriptor via JNI (sends UI_DEV_DESTROY internally) and marks
    // the device unavailable so subsequent calls are no-ops rather than crashing.
    @Synchronized override fun destroy() {
        imePolicies.restoreAll()
        cursorFeedback.cancel()
        readyTarget = null
        UinputNative.nClose()
        uinputReady = false
        // A Shizuku UserService is an app_process, not an Android Service. Closing
        // uinput alone leaves its VM/Binder threads alive after every replacement.
        kotlin.system.exitProcess(0)
    }

    companion object {
        val DEVICE_NAME = "AR Touchpad Mouse (${BuildConfig.APPLICATION_ID})"
        private const val TAG = "MouseService"

        const val UI_SET_EVBIT  = 0x40045564
        const val UI_SET_KEYBIT = 0x40045565
        const val UI_SET_RELBIT = 0x40045566
        const val UI_DEV_CREATE  = 0x5501
        const val UI_DEV_DESTROY = 0x5502

        const val EV_SYN = 0; const val EV_KEY = 1; const val EV_REL = 2
        const val REL_X = 0; const val REL_Y = 1; const val REL_HWHEEL = 6; const val REL_WHEEL = 8
        const val REL_WHEEL_HI_RES = 0x0b; const val REL_HWHEEL_HI_RES = 0x0c

        // Kernel convention: 120 hi-res wheel units make one detent (notch).
        const val HI_RES_UNITS_PER_DETENT = 120
        // 20 px of finger travel = 1 wheel detent, before the ViewModel's scrollSpeed factor.
        private const val SCROLL_PX_PER_DETENT = 20f
        private const val HI_RES_UNITS_PER_PX = HI_RES_UNITS_PER_DETENT / SCROLL_PX_PER_DETENT
        const val BTN_LEFT = 0x110; const val BTN_RIGHT = 0x111; const val BTN_MIDDLE = 0x112
        const val KEY_LEFTCTRL = 29
        const val KEY_BACK = 158; const val KEY_HOME = 102; const val KEY_APPSWITCH = 580
        const val SYN_REPORT = 0
    }
}
