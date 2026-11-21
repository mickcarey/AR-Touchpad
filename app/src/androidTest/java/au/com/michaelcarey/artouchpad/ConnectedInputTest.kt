package au.com.michaelcarey.artouchpad

import android.app.ActivityOptions
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.json.JSONObject
import org.json.JSONArray

/** Requires Shizuku permission and an external display. Never silently skips missing hardware. */
@RunWith(AndroidJUnit4::class)
class ConnectedInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.getSharedPreferences("touchpad_prefs", Context.MODE_PRIVATE)
    private lateinit var saved: Map<String, *>
    private lateinit var main: ActivityScenario<MainActivity>
    private var probe: ActivityScenario<InputProbeActivity>? = null
    private lateinit var vm: TouchpadViewModel

    @Before fun start() {
        saved = prefs.all.toMap()
        main = ActivityScenario.launch(Intent(context, MainActivity::class.java),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        main.onActivity {
            vm = ViewModelProvider(it)[TouchpadViewModel::class.java]
            if (!vm.mouse.hasPermission()) vm.requestShizukuPermission()
            vm.setPocketMode(false)
        }
        await("Mouse did not connect: check Shizuku and glasses") { vm.state.value.mouseReady }
    }
    @After fun stop() {
        probe?.close()
        if (::main.isInitialized) main.close()
        if (::saved.isInitialized) {
            val edit = prefs.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            edit.commit()
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue("$message (${if (::vm.isInitialized) vm.state.value.connectionMessage else "starting"})", condition())
    }
    private fun receiveInput() {
        val target = vm.state.value.targetDisplay!!
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.id)
        HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", 1)
        probe = ActivityScenario.launch(Intent(context, InputProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK), options.toBundle())
        InputProbeActivity.samples.clear(); InputProbeActivity.keys.clear()
        main.onActivity { vm.mouse.moveMouse(-10000f, -10000f); vm.mouse.flushMovement() }
        SystemClock.sleep(100)
        main.onActivity { vm.mouse.moveMouse(220f, 220f); vm.mouse.flushMovement() }
        await("Mouse did not reach the external test window") {
            InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_HOVER_MOVE || it.action == MotionEvent.ACTION_HOVER_ENTER }
        }
    }
    @Test fun nativePinchUsesTheActualPointerCoordinatesAndCtrlModifier() {
        receiveInput()
        val hover = InputProbeActivity.samples.last { it.action == MotionEvent.ACTION_HOVER_MOVE || it.action == MotionEvent.ACTION_HOVER_ENTER }
        main.onActivity { vm.mouse.ctrlScroll(0.4f) }
        await("No native zoom wheel received") { InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_SCROLL } }
        val zoom = InputProbeActivity.samples.last { it.action == MotionEvent.ACTION_SCROLL }
        assertEquals(hover.x, zoom.x, 1f); assertEquals(hover.y, zoom.y, 1f)
        assertTrue("Zoom lost its Ctrl modifier", zoom.meta and KeyEvent.META_CTRL_ON != 0)
        assertTrue(zoom.wheel > 0f)
        InputProbeActivity.samples.clear()
        main.onActivity { vm.mouse.scroll(0f, -10f); vm.mouse.flushScroll() }
        await("No ordinary scroll received") { InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_SCROLL } }
        assertEquals(0, InputProbeActivity.samples.last { it.action == MotionEvent.ACTION_SCROLL }.meta and KeyEvent.META_CTRL_ON)
    }
    @Test fun firstPhoneSwipeAfterResumeDrivesTheExternalPointer() {
        receiveInput()
        main.onActivity {
            vm.closeSettings()
            vm.updateInputSettings { it.copy(smoothing = Smoothing.OFF, sensitivity = 1f, precision = false) }
        }
        awaitPhoneFrame()
        val hover = InputProbeActivity.samples.last { it.action == MotionEvent.ACTION_HOVER_MOVE || it.action == MotionEvent.ACTION_HOVER_ENTER }
        InputProbeActivity.samples.clear()
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float) {
            main.onActivity { activity ->
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, 300f, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue("Phone did not consume touch action $action", activity.dispatchTouchEvent(event)) }
                finally { event.recycle() }
            }
        }
        touch(MotionEvent.ACTION_DOWN, 20f)
        SystemClock.sleep(20)
        touch(MotionEvent.ACTION_MOVE, 100f)
        SystemClock.sleep(20)
        touch(MotionEvent.ACTION_MOVE, 200f)
        assertEquals("Phone gesture was not recognised", TouchMode.CURSOR, vm.state.value.touchMode)
        touch(MotionEvent.ACTION_UP, 200f)
        await("First phone gesture did not move the external pointer") {
            InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_HOVER_MOVE && (it.x != hover.x || it.y != hover.y) }
        }
        assertFalse("An edge swipe opened settings", vm.state.value.showSettings)
    }

    @Test fun cancelledBottomEdgeDragReleasesAndNextSwipeMovesAtMinus90Degrees() =
        checkCancelledBottomEdgeDrag(dragLock = true)

    @Test fun cancelledBottomEdgeDragWithoutLockDoesNotCopyAtMinus90Degrees() =
        checkCancelledBottomEdgeDrag(dragLock = false)

    private fun checkCancelledBottomEdgeDrag(dragLock: Boolean) {
        receiveInput()
        var x = 0f
        var edgeY = 0f
        main.onActivity { activity ->
            vm.closeSettings()
            vm.setAxisRotation(AxisRotation.COUNTERCLOCKWISE_90)
            vm.updateInputSettings { it.copy(holdMs = 300, dragLock = dragLock, autoCopy = true,
                smoothing = Smoothing.OFF, sensitivity = 1f, precision = false) }
            val decor = activity.window.decorView
            x = decor.width / 2f
            edgeY = decor.height - decor.rootWindowInsets
                .getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom - 2f
        }
        awaitPhoneFrame()
        var downTime = 0L
        fun touch(action: Int, atX: Float, atY: Float) {
            main.onActivity { activity ->
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                val event = MotionEvent.obtain(downTime, now, action, atX, atY, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        touch(MotionEvent.ACTION_DOWN, x, edgeY - 64)
        SystemClock.sleep(400)
        touch(MotionEvent.ACTION_MOVE, x, edgeY)
        await("Long-press drag did not press the external mouse button") {
            InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_DOWN }
        }
        touch(MotionEvent.ACTION_CANCEL, x, edgeY)
        assertFalse("Android cancellation latched drag lock", vm.state.value.dragHeld)
        await("Cancelled drag left the external mouse button down") {
            InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_UP }
        }
        val release = InputProbeActivity.samples.last { it.action == MotionEvent.ACTION_UP }
        touch(MotionEvent.ACTION_DOWN, x, edgeY - 64)
        touch(MotionEvent.ACTION_MOVE, x + 40, edgeY - 64)
        touch(MotionEvent.ACTION_UP, x + 40, edgeY - 64)
        await("First fresh swipe did not move the external cursor upwards") {
            InputProbeActivity.samples.any { it.receivedAtNanos > release.receivedAtNanos &&
                it.action == MotionEvent.ACTION_HOVER_MOVE && it.y < release.y }
        }
        assertFalse("Cancelled drag copied the selection", InputProbeActivity.keys.any { it.first == KeyEvent.KEYCODE_C })
    }

    private fun awaitPhoneFrame() {
        instrumentation.waitForIdleSync()
        // Compose applies state/LaunchedEffect on a frame, after the main queue can be idle.
        val frames = java.util.concurrent.CountDownLatch(1)
        main.onActivity { activity ->
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { frames.countDown() }
            }
        }
        assertTrue("Phone surface did not draw", frames.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun systemRoutedEdgeSwipesKeepMovingTheExternalCursor() {
        receiveInput()
        main.onActivity {
            vm.closeSettings()
            vm.updateInputSettings { it.copy(smoothing = Smoothing.OFF, sensitivity = 1f, precision = false) }
        }
        awaitPhoneFrame()
        lateinit var bounds: android.graphics.Rect
        main.onActivity { bounds = it.windowManager.currentWindowMetrics.bounds }
        val automation = instrumentation.uiAutomation
        automation.waitForIdle(500, 10000)
        val width = bounds.width()
        val height = bounds.height()
        // Two top strokes in succession exercise a pull-down while transient bars may be visible.
        val strokes = listOf(
            intArrayOf(2, height / 3, 160, height / 3),
            intArrayOf(width - 3, height * 2 / 3, width - 160, height * 2 / 3),
            intArrayOf(width / 3, 2, width / 3, 160),
            intArrayOf(width / 3, 2, width / 3, 160),
        )
        for ((x1, y1, x2, y2) in strokes) {
            val previous = InputProbeActivity.samples.lastOrNull()
            InputProbeActivity.samples.clear()
            // Inject through InputDispatcher so SystemUI and the launcher can intercept.
            val command = "input touchscreen -d 0 swipe $x1 $y1 $x2 $y2 180"
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)
            ).bufferedReader().use { it.readText() }
            assertTrue(output, output.isBlank())
            await("Stroke from ($x1, $y1) lost the external pointer at $previous") {
                InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_HOVER_MOVE ||
                    it.action == MotionEvent.ACTION_HOVER_ENTER }
            }
        }
    }

    @Test fun topEdgePullsKeepStatusIconsHiddenAndShadeCollapsed() {
        receiveInput()
        main.onActivity {
            vm.closeSettings()
            vm.updateInputSettings {
                it.copy(smoothing = Smoothing.OFF, sensitivity = 0.3f, precision = false,
                    blackTouchpad = false, holdMs = 1200)
            }
            vm.setAxisRotation(AxisRotation.NONE)
            vm.setDimDelay(0)
        }
        awaitPhoneFrame()
        val automation = instrumentation.uiAutomation
        var statusHeight = 0
        var width = 0
        main.onActivity {
            val decor = it.window.decorView
            width = decor.width
            statusHeight = decor.rootWindowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
        }
        assertTrue(statusHeight > 0)
        data class Sample(val icons: IntArray, val belowBar: IntArray)
        val band = width / 3
        fun sample(): Sample {
            val screen = requireNotNull(automation.takeScreenshot())
            try {
                // Sample both icon areas, excluding the moving finger in the middle.
                val icons = IntArray(2 * band * statusHeight)
                screen.getPixels(icons, 0, 2 * band, 0, 0, band, statusHeight)
                screen.getPixels(icons, band, 2 * band, width - band, 0, band, statusHeight)
                val below = IntArray(band * statusHeight)
                screen.getPixels(below, 0, band, 0, 2 * statusHeight, band, statusHeight)
                return Sample(icons, below)
            } finally { screen.recycle() }
        }
        automation.waitForIdle(500, 5000)
        val before = sample()
        fun onlyBackgroundTint(pixels: IntArray): Boolean {
            // SystemUI may tint the empty strip. Across one scanline, identical underlying
            // grid pixels must still have the same colour. A clock or glyph breaks that mapping.
            val colours = HashMap<Int, Int>()
            for (i in pixels.indices) {
                if (i % (band * 2) == 0) colours.clear()
                val pixel = pixels[i]
                val expected = colours.putIfAbsent(before.icons[i], pixel) ?: continue
                for (shift in 0..16 step 8) {
                    val channel = (pixel ushr shift) and 255
                    if (kotlin.math.abs(channel - ((expected ushr shift) and 255)) > 3) return false
                }
            }
            return true
        }
        // Positive control: a bright mark in the clock region must fail the icon check.
        val visibleMark = before.icons.clone().apply { fill(android.graphics.Color.WHITE, band / 4, band / 4 + 12) }
        assertFalse(onlyBackgroundTint(visibleMark))
        fun assertClear(stage: String) {
            val frame = sample()
            assertTrue("Clock or status icons appeared $stage", onlyBackgroundTint(frame.icons))
            assertArrayEquals("Notification shade covered the pad $stage", before.belowBar, frame.belowBar)
        }
        repeat(2) { stroke ->
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, width / 2f, y, 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    HiddenApiBypass.invoke(android.view.InputEvent::class.java, event, "setDisplayId", Display.DEFAULT_DISPLAY)
                    assertTrue(automation.injectInputEvent(event, true))
                } finally { event.recycle() }
            }
            InputProbeActivity.samples.clear()
            send(MotionEvent.ACTION_DOWN, 1f)
            try {
                for (step in 1..8) {
                    SystemClock.sleep(16)
                    send(MotionEvent.ACTION_MOVE, 1f + step * 32f)
                }
                SystemClock.sleep(250)
                assertClear("during top-edge drag ${stroke + 1}")
            } finally { send(MotionEvent.ACTION_UP, 257f) }
            assertClear("after lifting on stroke ${stroke + 1}")
            await("Top-edge drag ${stroke + 1} did not move the external pointer") {
                InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_HOVER_MOVE }
            }
        }
    }

    private fun fourFingerTap() {
        val properties = Array(4) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(4) { index -> MotionEvent.PointerCoords().apply {
            x = 200f + index * 120f; y = 500f; pressure = 1f; size = 1f
        } }
        main.onActivity { activity ->
            // A 70 ms tap regardless of how long the emulator takes to draw external settings.
            val down = SystemClock.uptimeMillis() - 70
            var time = down
            fun send(action: Int, count: Int) {
                val event = MotionEvent.obtain(down, time, action, count,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                time += 10
            }
            send(MotionEvent.ACTION_DOWN, 1)
            for (count in 2..4) send(MotionEvent.ACTION_POINTER_DOWN or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count)
            for (count in 4 downTo 2) send(MotionEvent.ACTION_POINTER_UP or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count)
            send(MotionEvent.ACTION_UP, 1)
        }
    }

    @Test fun fourFingerTapTogglesExternalSettingsAndRestoresPocketLock() {
        for (locked in listOf(false, true)) {
            main.onActivity { vm.closeSettings(); vm.setPocketMode(locked) }
            awaitPhoneFrame()
            fourFingerTap()
            await("Four-finger tap did not open settings") { vm.state.value.showSettings }
            assertFalse(vm.state.value.pocketInputLocked)
            assertTrue(vm.state.value.touchpadInputEnabled)
            awaitPhoneFrame()
            fourFingerTap()
            await("Second four-finger tap did not close settings") { !vm.state.value.showSettings }
            assertEquals(locked, vm.state.value.pocketMode)
            assertEquals(locked, vm.state.value.pocketInputLocked)
            assertEquals(!locked, vm.state.value.touchpadInputEnabled)
        }
    }
    private fun externalStatusText(target: Int, title: String): String? {
        var text: String? = null
        instrumentation.runOnMainSync {
            // Click-through windows can be absent from the accessibility interactive-window list.
            text = android.view.inspector.WindowInspector.getGlobalWindowViews().filterIsInstance<android.widget.TextView>()
                .firstOrNull { it.isShown && it.display?.displayId == target &&
                    (it.layoutParams as? android.view.WindowManager.LayoutParams)?.title == title }
                ?.contentDescription?.toString()
        }
        return text
    }

    @Test fun debugUatNoticeAppearsOnExternalDisplayOncePerUpdate() {
        org.junit.Assume.assumeTrue(BuildConfig.BUILD_TYPE == "debug")
        val notice = context.getSharedPreferences("uat_notice", Context.MODE_PRIVATE)
        val savedNotice = notice.all.toMap()
        try {
            notice.edit().clear().commit()
            val target = vm.state.value.targetDisplay!!.id
            fun announce() = main.onActivity { activity ->
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("uat_ready", true))
            }
            announce()
            await("New Debug build was not announced on the selected monitor") {
                externalStatusText(target, "AR Touchpad UAT notice")?.contains("Debug ready for UAT") == true
            }
            await("UAT notice was not acknowledged and dismissed") {
                notice.getLong("shown", 0L) > 0L && externalStatusText(target, "AR Touchpad UAT notice") == null
            }
            announce()
            instrumentation.waitForIdleSync()
            assertEquals(0L, notice.getLong("pending", 0L))
            assertNull(externalStatusText(target, "AR Touchpad UAT notice"))
        } finally {
            val editor = notice.edit().clear()
            savedNotice.forEach { (key, value) -> if (value is Long) editor.putLong(key, value) }
            editor.commit()
        }
    }

    @Test fun externalStatusTracksPocketLockAndTheMicrophoneSession() {
        org.junit.Assume.assumeTrue("Grant RECORD_AUDIO on the test emulator to exercise capture",
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        receiveInput()
        val target = vm.state.value.targetDisplay!!.id
        fun statusText() = externalStatusText(target, "AR Touchpad display status")
        main.onActivity { vm.closeSettings(); vm.setPocketMode(true) }
        await("Pocket lock indicator absent") { statusText() == "Pocket locked" }
        assertTrue(InputProbeActivity.focused)
        main.onActivity { vm.updateLaunchpad { it.copy(roomMeter = true) }; vm.setPocketMode(false) }
        await("Microphone did not produce a level or digital silence") {
            vm.roomLevel.value.rms != null || vm.roomLevel.value.status == "No mic signal"
        }
        await("Mic status absent from external display") {
            statusText()?.let { "dBFS" in it || it == "No mic signal" } == true
        }
        assertTrue(InputProbeActivity.focused)
        main.onActivity { vm.updateLaunchpad { it.copy(roomMeter = false) } }
        await("Status stayed visible after disabling the meter") { statusText() == null }
    }

    @Test fun registeredShortcutLaunchesOnTheSelectedDisplay() {
        val extension = object : LauncherExtension {
            override val namespace = "test"
            override fun entries(context: Context) = listOf(AppShortcut("probe", "Probe",
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888),
                Intent(context, InputProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)))
        }
        val launcher = AppLauncher(extension)
        val shortcut = launcher.catalog(context).single()
        assertEquals("test/probe", shortcut.id)
        val target = vm.state.value.targetDisplay!!.id
        main.onActivity { launcher.launch(it, shortcut, target) }
        try {
            await("Shortcut did not open on target display") {
                InputProbeActivity.resumed && InputProbeActivity.activeDisplayId == target
            }
        } finally {
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<InputProbeActivity>().forEach { it.finish() }
            }
        }
    }

    @Test fun settingsOverlayLeavesExternalAppResumedAndFocused() {
        receiveInput()
        await("External probe did not gain focus") { InputProbeActivity.resumed && InputProbeActivity.focused }
        val pauses = InputProbeActivity.pauseCount
        main.onActivity { vm.openSettings() }
        var overlay: SettingsOverlay? = null
        await("External overlay not shown") {
            main.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("settingsOverlay").apply { isAccessible = true }
                overlay = field.get(activity) as? SettingsOverlay
            }
            overlay?.isShowing == true
        }
        SystemClock.sleep(600)
        assertTrue("Opening settings paused the external app", InputProbeActivity.resumed)
        assertTrue("Opening settings stole external keyboard focus", InputProbeActivity.focused)
        assertEquals("External app received onPause", pauses, InputProbeActivity.pauseCount)
        main.onActivity {
            val attrs = overlay!!.window!!.attributes
            assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, attrs.type)
            assertEquals(0, attrs.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertTrue(attrs.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals(0f, attrs.dimAmount, 0f)
            assertEquals(vm.state.value.targetDisplay!!.id, overlay!!.display.displayId)
            // Capture the real external overlay for visual review, not the phone fallback.
            val view = overlay!!.window!!.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(context.getExternalFilesDir(null), "settings-overlay.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            vm.closeSettings()
        }
        await("Overlay did not dismiss") { overlay?.isShowing == false }
        assertTrue(InputProbeActivity.focused)
    }

    @Test fun switchFromGlassesSettingsLaunchesProdOnPhone() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("buildSwitch") == "true")
        assertNotNull("Install the Prod APK on the test emulator", TouchpadBuild.Prod.launchIntent(context))
        receiveInput()
        val externalDisplay = vm.state.value.targetDisplay!!.id
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        try {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            main.onActivity { vm.openSettings() }
            var button: AccessibilityNodeInfo? = null
            fun switchLabel(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString() == "Switch to Prod") return node
                for (i in 0 until node.childCount) switchLabel(node.getChild(i))?.let { return it }
                return null
            }
            await("No switch to Prod on the external display") {
                val windows = automation.windowsOnAllDisplays[externalDisplay].orEmpty()
                val label = windows.mapNotNull { it.root }.filter { it.packageName == context.packageName }
                    .firstNotNullOfOrNull(::switchLabel)
                button = generateSequence(label) { it.parent }.firstOrNull { it.isClickable }
                button != null
            }
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Prod did not open on the phone") {
                automation.windowsOnAllDisplays[Display.DEFAULT_DISPLAY].orEmpty()
                    .any { it.root?.packageName == TouchpadBuild.Prod.packageId }
            }
            assertFalse("Debug still owns the mouse", vm.mouse.isDisplayReady)
            assertFalse("Debug settings stayed open", vm.state.value.showSettings)
            assertTrue("Switching displaced the external app", InputProbeActivity.resumed)
        } finally {
            info.flags = originalFlags
            automation.serviceInfo = info
        }
    }

    @Test fun mediaSessionPlaybackStateAndCommandsUseTheSamePlayer() {
        val serviceField = ShizukuMouseController::class.java.getDeclaredField("service").apply { isAccessible = true }
        val service = serviceField.get(vm.mouse) as IMouseService
        val session = android.media.session.MediaSession(context, "AR Touchpad media test")
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun playback(state: Int) {
            session.setPlaybackState(android.media.session.PlaybackState.Builder().setState(state, 0, 1f)
                .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS).build())
        }
        try {
            session.setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPause() { commands += "pause" }
                override fun onPlay() { commands += "play" }
                override fun onSkipToNext() { commands += "next" }
                override fun onSkipToPrevious() { commands += "previous" }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            playback(android.media.session.PlaybackState.STATE_PLAYING)
            session.setMetadata(android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Test film")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Test artist").build())
            session.isActive = true
            await("Test media session not selected; will not send commands to another player") {
                service.mediaState.getString("sessionPackage") == context.packageName
            }
            assertEquals("Test film", service.mediaState.getString("title"))
            assertEquals("Test artist", service.mediaState.getString("subtitle"))
            assertEquals(1, service.mediaState.getInt("playing", -1))
            assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_MEDIA_PAUSE))
            // Keep the fixture playing until routing assertions finish: a genuinely playing
            // app (e.g. Spotify) should take over when this fixture stops. Never pause it.
            await("Pause not received") { commands.contains("pause") }
            assertEquals(context.packageName, service.mediaState.getString("sessionPackage"))
            assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_MEDIA_PLAY))
            await("Play not received") { commands.contains("play") }
            assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_MEDIA_NEXT))
            assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            await("Track controls not received") { commands.containsAll(listOf("next", "previous")) }
            playback(android.media.session.PlaybackState.STATE_STOPPED)
            await("Stopped session still reported as playing") {
                val observed = service.mediaState
                observed.getString("sessionPackage") != context.packageName || observed.getInt("playing", -1) == 0
            }
            assertTrue(service.mediaState.getInt("maxVolume") > 0)
        } finally { session.release() }
    }

    @Test fun mediaVolumeAndMuteUseObservedMusicStreamStateWithoutPhonePanel() {
        receiveInput()
        main.onActivity { vm.openSettings() }
        val serviceField = ShizukuMouseController::class.java.getDeclaredField("service").apply { isAccessible = true }
        val service = serviceField.get(vm.mouse) as IMouseService
        val original = service.mediaState
        assertTrue("Music volume unavailable", original.getInt("maxVolume") > 0)
        val wasMuted = original.getBoolean("muted")
        var audibleVolume: Int? = null
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_VOLUME_MUTE))
            await("Mute state did not change") { service.mediaState.getBoolean("muted") != wasMuted }
            if (!wasMuted) {
                assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_VOLUME_MUTE))
                await("Unmute did not restore audio") { !service.mediaState.getBoolean("muted") }
            }
            audibleVolume = service.mediaState.getInt("volume")
            if (audibleVolume > 0) {
                assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_VOLUME_DOWN))
                await("Volume down not reflected") { service.mediaState.getInt("volume") < audibleVolume }
                assertTrue(service.sendMediaControl(KeyEvent.KEYCODE_VOLUME_UP))
                await("Volume up not reflected") { service.mediaState.getInt("volume") == audibleVolume }
            }
            // No phone-side SystemUI volume window should be visible after these commands.
            val windows = shell("dumpsys window windows").split(Regex("(?m)^  Window #"))
            assertFalse("Volume operation opened Android's volume panel", windows.any {
                it.lineSequence().firstOrNull()?.contains("VolumeDialog", ignoreCase = true) == true &&
                    it.contains("isOnScreen=true")
            })
            await("Settings volume display did not refresh") { vm.state.value.media.maxVolume > 0 &&
                vm.state.value.media.volume == service.mediaState.getInt("volume") }
        } finally {
            audibleVolume?.let { shell("cmd media_session volume --stream 3 --set $it") }
            if (service.mediaState.getBoolean("muted") != wasMuted) service.sendMediaControl(KeyEvent.KEYCODE_VOLUME_MUTE)
            main.onActivity { vm.closeSettings() }
        }
    }
    @Test fun cancelledDragReleasesButtonAndNeverCopies() {
        receiveInput()
        main.onActivity { vm.updateInputSettings { it.copy(autoCopy = true) }; vm.startSelectDrag() }
        await("Mouse down missing") { InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_DOWN } }
        main.onActivity { vm.cancelSelectDrag() }
        await("Mouse up missing") { InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_UP } }
        assertFalse(InputProbeActivity.keys.any { it.first == KeyEvent.KEYCODE_C })
    }
    @Test fun completedDragOnlyCopiesWhenPreferenceEnabledAndReleasesCtrl() {
        receiveInput()
        main.onActivity { vm.updateInputSettings { it.copy(autoCopy = false) }; vm.startSelectDrag(); vm.endSelectDrag() }
        await("Mouse up missing") { InputProbeActivity.samples.any { it.action == MotionEvent.ACTION_UP } }
        assertFalse(InputProbeActivity.keys.any { it.first == KeyEvent.KEYCODE_C })
        main.onActivity { vm.updateInputSettings { it.copy(autoCopy = true) }; vm.startSelectDrag(); vm.endSelectDrag() }
        await("Copy modifier did not release") { InputProbeActivity.keys.contains(KeyEvent.KEYCODE_CTRL_LEFT to KeyEvent.ACTION_UP) }
        assertTrue(InputProbeActivity.keys.contains(KeyEvent.KEYCODE_C to KeyEvent.ACTION_DOWN))
        assertTrue(InputProbeActivity.keys.contains(KeyEvent.KEYCODE_C to KeyEvent.ACTION_UP))
    }
    @Test fun retryRecreatesMouseAndRestoresDisplayAssociation() {
        fun deviceId() = InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice)
            .firstOrNull { it.name == MouseService.DEVICE_NAME }?.id
        val before = deviceId()
        main.onActivity { vm.retryConnection() }
        await("Retry did not restore mouse") { vm.state.value.mouseReady && deviceId() != null && deviceId() != before }
        assertTrue(vm.state.value.targetDisplay!!.id != 0)
    }
    @Test fun profilesAndResetKeepConnectionUsableAndResetSavedCalibration() {
        main.onActivity {
            vm.setPocketMode(true); vm.openSettings()
            vm.applyProfile(UsageProfile.MOVIE)
            assertTrue(vm.state.value.pocketMode)
            assertEquals(ThreeFingerAction.PLAY_PAUSE, vm.state.value.threeFingerAction)
            vm.startOrientationCalibration(); vm.finishOrientationCalibration(37f)
            val beforeReset = vm.state.value.inputEpoch
            vm.resetDefaults()
            assertTrue(vm.state.value.inputEpoch > beforeReset)
            assertFalse(vm.state.value.pocketMode)
            assertNull(vm.state.value.calibratedRotationDegrees)
            assertEquals(InputSettings(), vm.state.value.inputSettings())
        }
        await("Reset disrupted mouse") { vm.state.value.mouseReady }
    }

    @Test fun lostDeviceReportsFailureAndRetryRecoversWithoutRestartingShizuku() {
        val serviceField = ShizukuMouseController::class.java.getDeclaredField("service").apply { isAccessible = true }
        val service = serviceField.get(vm.mouse) as IMouseService
        service.clearDisplay(null)
        await("Lost device remained marked ready") { !vm.state.value.mouseReady }
        assertTrue(vm.state.value.connectionMessage.contains("Retry", ignoreCase = true))
        main.onActivity { vm.retryConnection() }
        await("Lost device did not recover") { vm.state.value.mouseReady }
    }

    @Test fun keyboardPolicyRestoresOnBackgroundAndReappliesOnResume() {
        main.onActivity { vm.setDexKeyboard(true) }
        await("Phone keyboard policy did not apply") { vm.state.value.dexKeyboardActive }
        main.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        await("Background retained mouse readiness") { !vm.state.value.mouseReady }
        main.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await("Phone keyboard policy did not reapply") { vm.state.value.mouseReady && vm.state.value.dexKeyboardActive }
    }

    /** Explicit opt-in: -e performance true. Never treats missing hardware as a passed test. */
    @Test fun performanceSample() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("performance") == "true")
        receiveInput()
        main.onActivity { vm.updateInputSettings { it.copy(smoothing = Smoothing.OFF, sensitivity = 1f, precision = false) } }
        val latency = mutableListOf<Double>()
        val allocatedBefore = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
        for (i in 0 until 60) {
            SystemClock.sleep(20)
            val start = SystemClock.elapsedRealtimeNanos()
            main.onActivity { vm.moveCursor(if (i % 2 == 0) 4f else -4f, 0f, SystemClock.uptimeMillis()) }
            await("Pointer delivery stalled") { InputProbeActivity.samples.any {
                it.action == MotionEvent.ACTION_HOVER_MOVE && it.receivedAtNanos >= start
            } }
            val delivered = InputProbeActivity.samples.first { it.action == MotionEvent.ACTION_HOVER_MOVE && it.receivedAtNanos >= start }
            latency += (delivered.receivedAtNanos - start) / 1_000_000.0
        }
        val allocated = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocatedBefore
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        val duration = InstrumentationRegistry.getArguments().getString("idleMs")?.toLongOrNull()?.coerceIn(1000, 600_000) ?: 60_000L
        val modes = JSONArray()
        for (mode in listOf("normal", "black", "pocket")) {
            main.onActivity {
                vm.setDimDelay(0); vm.setBlackTouchpad(mode != "normal"); vm.setPocketMode(mode == "pocket")
            }
            SystemClock.sleep(2500) // exclude transient lock badge and policy changes
            val charge = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val cpu = android.os.Process.getElapsedCpuTime()
            val start = SystemClock.elapsedRealtime()
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Measuring $mode for ${duration / 1000}s\n") })
            SystemClock.sleep(duration)
            modes.put(JSONObject().put("mode", mode).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                .put("app_cpu_ms", android.os.Process.getElapsedCpuTime() - cpu)
                .put("charge_used_uah", if (charge == Int.MIN_VALUE) JSONObject.NULL else charge -
                    battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)))
        }
        val sorted = latency.sorted()
        val report = JSONObject().put("build_type", BuildConfig.BUILD_TYPE).put("device", android.os.Build.MODEL)
            .put("samples", latency.size).put("software_input_median_ms", sorted[sorted.size / 2])
            .put("software_input_p95_ms", sorted[(sorted.size * 0.95).toInt()])
            .put("process_allocated_bytes_including_test_harness", allocated).put("idle_modes", modes)
            .put("limitations", "Software delivery only; excludes digitizer and scanout. Battery includes phone plus glasses; short samples are noisy. CPU excludes the shell service.")
        java.io.File(context.getExternalFilesDir(null), "performance.json").writeText(report.toString(2))
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", report.toString(2) + "\n") })
    }
}
