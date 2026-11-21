package au.com.michaelcarey.artouchpad

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.michaelcarey.artouchpad.ui.SettingsPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val mediaKeys = listOf(
        "Previous track" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "Play / pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "Next track" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "Volume down" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "Mute / unmute" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "Volume up" to KeyEvent.KEYCODE_VOLUME_UP,
    )

    private fun mediaPanel(width: Int, enabled: Boolean = true, onKey: (Int) -> Unit = {}, onDismiss: () -> Unit = {},
        availableBuild: TouchpadBuild? = null, onSwitchBuild: (TouchpadBuild) -> Unit = {}) {
        compose.setContent {
            // Exercise phone and glasses widths on the same physical test device.
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.width(width.dp).height(700.dp)) {
                    SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                        false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, onDismiss, {}, {},
                        { Text("Bottom marker") }, mediaEnabled = enabled, onMediaKey = onKey,
                        availableBuild = availableBuild, onSwitchBuild = onSwitchBuild)
                }
            }
        }
    }

    private fun verifyPinnedMedia(width: Int) {
        val received = mutableListOf<Int>()
        val switches = mutableListOf<TouchpadBuild>()
        mediaPanel(width, onKey = received::add, availableBuild = TouchpadBuild.Prod, onSwitchBuild = switches::add)
        val before = mediaKeys.map { (label, _) ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
        }
        val header = compose.onNodeWithTag("settings-header").getUnclippedBoundsInRoot()
        val mediaCenter = (before.first().left.value + before.last().right.value) / 2f
        assertEquals("Media controls are not centered in the header", (header.left.value + header.right.value) / 2f, mediaCenter, 1f)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Bottom marker").performScrollTo().assertIsDisplayed()
        val inHeader = hasAnyAncestor(hasTestTag("settings-header"))
        if (TouchpadBuild.current == TouchpadBuild.Debug) {
            compose.onNodeWithText("Debug Build Version ${BuildConfig.VERSION_NAME.removeSuffix("-debug")}").assertIsDisplayed()
        }
        compose.onNode(hasContentDescription("Switch to Prod") and inHeader).assertIsDisplayed().performClick()
        compose.onNodeWithText("Switch to Prod").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(TouchpadBuild.Prod), switches) }
        mediaKeys.forEachIndexed { index, (label, _) ->
            val control = compose.onNodeWithContentDescription(label).assertIsDisplayed()
            assertEquals("Header moved while settings scrolled", before[index], control.getUnclippedBoundsInRoot())
            control.performClick()
        }
        compose.runOnIdle { assertEquals(mediaKeys.map { it.second }, received) }
        compose.onNodeWithText("Play / pause").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "media-header-$width.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun phoneMediaHeaderStaysVisibleAndSendsPreviousNextAndMute() = verifyPinnedMedia(360)
    @Test fun wideOverviewAlignsDateActionsAndSettingsTabs() {
        mediaPanel(1000)
        val apps = compose.onNodeWithTag("apps-pane").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("settings-header").getUnclippedBoundsInRoot()
        assertEquals(header.left.value + 24f, apps.left.value, 1f)
        assertEquals(header.right.value - 24f, apps.right.value, 1f)
        compose.onNodeWithText("Launchpad").assertDoesNotExist()
        val time = compose.onNodeWithTag("launchpad-time").assertIsDisplayed().getUnclippedBoundsInRoot()
        val info = compose.onNodeWithContentDescription("Information").getUnclippedBoundsInRoot()
        val cog = compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        val date = compose.onNodeWithTag("launchpad-date").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(time.left.value, date.left.value, 1f)
        assertTrue(time.bottom <= date.top)
        assertTrue(time.bottom - time.top > date.bottom - date.top)
        assertTrue((cog.left - info.right).value in 0f..16f)
        assertEquals(cog.top.value, info.top.value, 1f)
        assertEquals(apps.left.value, time.left.value, 1f)
        assertEquals(apps.right.value, cog.right.value, 1f)
        compose.onNodeWithContentDescription("Close settings").assertDoesNotExist()
        compose.onNodeWithText("App library").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").performClick()
        val firstTab = compose.onNodeWithText("Personalise").assertIsSelected().getUnclippedBoundsInRoot()
        val lastTab = compose.onNodeWithText("Connection").getUnclippedBoundsInRoot()
        assertTrue(firstTab.top >= header.bottom + 12.dp)
        assertEquals((header.left.value + header.right.value) / 2, (firstTab.left.value + lastTab.right.value) / 2, 1f)
        compose.onNodeWithContentDescription("Close settings").assertDoesNotExist()
        val home = compose.onNodeWithContentDescription("Home")
        assertEquals(cog, home.getUnclippedBoundsInRoot())
        home.performClick()
        compose.onNodeWithTag("apps-pane").assertExists()
        home.assertDoesNotExist()
    }
    @Test fun dateLabelFollowsLocalTimeAndHourPreference() {
        val brisbane = java.time.ZonedDateTime.parse("2026-11-21T20:21:00+10:00[Australia/Brisbane]")
        fun label(time: java.time.ZonedDateTime, use24Hours: Boolean) =
            au.com.michaelcarey.artouchpad.ui.launchpadTimeAndDate(time, java.util.Locale.ENGLISH, use24Hours)
        assertEquals("8:21pm" to "Sat, 21 Nov 2026", label(brisbane, false))
        assertEquals("20:21" to "Sat, 21 Nov 2026", label(brisbane, true))
        val honolulu = brisbane.plusHours(15).withZoneSameInstant(java.time.ZoneId.of("Pacific/Honolulu"))
        assertEquals("3:21pm" to "Sat, 21 Nov 2026", label(honolulu, false))
    }
    @Test fun wideMediaHeaderStaysVisibleAndSendsPreviousNextAndMute() = verifyPinnedMedia(1000)
    @OptIn(ExperimentalTestApi::class)
    @Test fun overviewAndSettingsAreSeparateAndTitleIsOptional() {
        val visible = androidx.compose.runtime.mutableStateOf(false)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        var opened: MediaState? = null
        val title = "Night train — Live from the concert hall, with the full ensemble and special guests"
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                mediaEnabled = true, mediaState = MediaState(title = title, subtitle = "Kyoto", sourcePackage = context.packageName),
                onOpenPlayer = { opened = it }, showTitle = visible.value, apps = { _, _ -> Text("Add App") }, appearance = { Text("Appearance") })
        }
        compose.onNodeWithText("Add App").assertIsDisplayed()
        compose.onNodeWithText("Appearance").assertDoesNotExist()
        compose.onNodeWithTag("now-playing").assertDoesNotExist()
        fun assertAppsCentred(contentTop: androidx.compose.ui.unit.Dp) {
            val apps = compose.onNodeWithTag("apps-pane").getUnclippedBoundsInRoot()
            val panel = compose.onNodeWithTag("launcher-panel").getUnclippedBoundsInRoot()
            assertEquals((apps.top - contentTop).value, (panel.bottom - apps.bottom).value, 1f)
        }
        assertAppsCentred(compose.onNodeWithTag("settings-header").getUnclippedBoundsInRoot().bottom + 1.dp)
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { getLayout ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(getLayout(layouts))
            assertFalse(layouts.single().hasVisualOverflow)
            assertEquals(androidx.compose.ui.text.style.TextAlign.Center, layouts.single().layoutInput.style.textAlign)
        }
        compose.onNodeWithText("Now playing").assertDoesNotExist()
        val artist = compose.onNodeWithText("Kyoto", useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot().bottom, artist.top)
        val header = compose.onNodeWithTag("settings-header").getUnclippedBoundsInRoot()
        val strip = compose.onNodeWithTag("now-playing").getUnclippedBoundsInRoot()
        assertEquals(header.left, strip.left)
        assertEquals(header.right, strip.right)
        assertEquals((strip.left + strip.right).value / 2f, (artist.left + artist.right).value / 2f, 1f)
        assertEquals(1f, (strip.top - header.bottom).value, 0.5f)
        assertAppsCentred(strip.bottom)
        val sourceLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(sourceLabel).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onNodeWithContentDescription(sourceLabel).getUnclippedBoundsInRoot().right <=
            compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot().left)
        val playback = compose.onNodeWithText(title)
        val resting = playback.captureToImage().asAndroidBitmap()
        playback.performMouseInput { enter(center) }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("Hover changed the playback background", resting.sameAs(playback.captureToImage().asAndroidBitmap()))
        playback.performMouseInput { click() }
        compose.runOnIdle { assertEquals(context.packageName, opened?.sourcePackage) }
        val appsPane = compose.onNodeWithTag("apps-pane").getUnclippedBoundsInRoot()
        val logo = compose.onNodeWithContentDescription(sourceLabel).getUnclippedBoundsInRoot()
        assertEquals(appsPane.left, logo.left)
        assertTrue(logo.right - logo.left >= 44.dp)
        java.io.File(context.getExternalFilesDir(null), "now-playing-panel.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("Add App").assertDoesNotExist()
        compose.onNodeWithTag("now-playing").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun coverTracksThePlayingItemAndAlignsToQuickLaunch() {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val media = androidx.compose.runtime.mutableStateOf(MediaState(title = "Song", sourcePackage = "com.spotify.music", artworkKey = "album"))
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                mediaEnabled = true, mediaState = media.value, showTitle = true,
                loadArtwork = { key -> bitmap.takeIf { key == "album" || key == "movie" } })
        }
        fun checkCover(label: String) {
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
            val cover = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertEquals(compose.onNodeWithTag("apps-pane").getUnclippedBoundsInRoot().right, cover.right)
        }
        checkCover("Album cover")
        compose.runOnIdle { media.value = MediaState(title = "Film", sourcePackage = "org.jellyfin.mobile", artworkKey = "movie") }
        checkCover("Jellyfin cover")
        compose.onNodeWithContentDescription("Album cover").assertDoesNotExist()
        compose.runOnIdle { media.value = media.value.copy(title = "Next film", artworkKey = "missing") }
        compose.onNodeWithContentDescription("Jellyfin cover").assertDoesNotExist()
    }

    @Test fun disconnectedMediaControlsStayVisibleButDisabled() {
        mediaPanel(360, enabled = false)
        compose.onNodeWithContentDescription("Switch to Prod").assertDoesNotExist()
        if (TouchpadBuild.current == TouchpadBuild.Debug) compose.onNodeWithText("Debug Build Version ${BuildConfig.VERSION_NAME.removeSuffix("-debug")}").assertIsDisplayed()
        mediaKeys.forEach { (label, _) -> compose.onNodeWithContentDescription(label).assertIsDisplayed().assertIsNotEnabled() }
    }
    @Test fun mediaIconsFollowObservedPlaybackMuteAndVolumeChanges() {
        val state = androidx.compose.runtime.mutableStateOf(MediaState(playing = false, muted = false, volume = 6, maxVolume = 12))
        val received = mutableListOf<Int>()
        compose.setContent {
            au.com.michaelcarey.artouchpad.ui.SettingsHeader(true, received::add, {}, state.value)
        }
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Mute").performClick()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_VOLUME_MUTE), received)
            state.value = state.value.copy(playing = true, muted = true)
        }
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Unmute").performClick()
        compose.onNodeWithText("Muted").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_VOLUME_MUTE), received)
            state.value = state.value.copy(playing = false, muted = false, volume = 9)
        }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mute").assertIsDisplayed()
        compose.onNodeWithText("75%").assertIsDisplayed()
    }
    @OptIn(ExperimentalTestApi::class)
    @Test fun canPinAndRemoveAnInstalledLauncherActivity() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = AppLauncher(InstalledApps).catalog(context)
        val (first, second) = catalog.groupBy { it.label }.values.filter { it.size == 1 }.take(2).map { it.single() }
        val pins = androidx.compose.runtime.mutableStateOf(emptySet<String>())
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onRemoveShortcuts = { pins.value -= it },
                apps = { open, requestRemoval ->
                    au.com.michaelcarey.artouchpad.ui.PinnedApps(catalog, pins.value, open, requestRemoval, { fail("Opening the menu launched the app") })
                },
                appPicker = { dismiss ->
                    au.com.michaelcarey.artouchpad.ui.AppPicker(catalog, pins.value, dismiss) {
                        pins.value += it
                        dismiss()
                    }
                })
        }
        compose.onNodeWithText("Add App").performClick()
        compose.onNodeWithText("Add App").assertIsNotEnabled()
        val search = compose.onNodeWithTag("app-search")
        val searchBounds = search.getUnclippedBoundsInRoot()
        val cancelBounds = compose.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("app-results").performScrollToNode(hasText(catalog.last().label))
        assertEquals(searchBounds, search.getUnclippedBoundsInRoot())
        assertEquals(cancelBounds, compose.onNodeWithText("Cancel").assertIsDisplayed().getUnclippedBoundsInRoot())
        search.performTextReplacement(first.label.uppercase())
        compose.onNode(hasText(first.label) and isToggleable()).performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(pins.value.isEmpty()) }
        compose.onNodeWithText("Add App").performClick()
        compose.onNodeWithTag("app-results").performScrollToNode(hasText(first.label))
        compose.onNode(hasText(first.label) and isToggleable()).performClick()
        compose.onNodeWithText("Add App").assertIsEnabled()
        search.performTextReplacement(second.label)
        compose.onNode(hasText(second.label) and isToggleable()).performClick()
        search.performTextReplacement("zzzz-no-such-app")
        compose.onNodeWithText("No matching apps").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Selected").performClick()
        compose.onNode(hasText(first.label) and isToggleable()).assertIsOn()
        compose.onNode(hasText(second.label) and isToggleable()).assertIsOn()
        compose.onNodeWithText("Add 2 Apps").performClick()
        compose.runOnIdle { assertEquals(setOf(first.id, second.id), pins.value) }
        compose.onNodeWithText(first.label).performTouchInput { longClick() }
        compose.onNodeWithText("Remove shortcut").performClick()
        compose.onNodeWithText("Remove ${first.label} from quick launch?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(setOf(first.id, second.id), pins.value) }
        compose.onNodeWithText(first.label).performMouseInput { rightClick() }
        compose.onNodeWithText("Remove shortcut").performClick()
        compose.onNodeWithText("The app stays installed on your device.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals(setOf(second.id), pins.value) }
        compose.onNodeWithText("Add App").performClick()
        search.performTextReplacement(second.label)
        compose.onNode(hasText(second.label) and isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Add App").assertIsNotEnabled()
    }

    @Test fun pinnedRowsAreCentredAndWrapAtEightTilesOrTheAvailableWidth() {
        val icon = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val apps = (1..11).map { AppShortcut("test/$it", "App $it", icon, android.content.Intent()) }
        val width = androidx.compose.runtime.mutableStateOf(1072.dp)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.width(width.value).testTag("pins-container")) {
                    au.com.michaelcarey.artouchpad.ui.PinnedApps(apps, apps.map { it.id }.toSet(), {}, {}, {})
                }
            }
        }
        fun checkRows(tilesPerRow: Int) {
            val panel = compose.onNodeWithTag("pins-container").getUnclippedBoundsInRoot()
            val tiles = (listOf("Add App") + apps.map { it.label }).map {
                compose.onNodeWithText(it).getUnclippedBoundsInRoot()
            }
            val rows = tiles.groupBy { it.top }.values
            assertEquals(tiles.chunked(tilesPerRow).map { it.size }, rows.map { it.size })
            rows.zipWithNext().forEach { (above, below) ->
                assertEquals(20f, (below.first().top - above.first().bottom).value, 1f)
            }
            for (row in rows) {
                assertEquals((row.first().left - panel.left).value, (panel.right - row.last().right).value, 1f)
            }
        }
        checkRows(8)
        compose.runOnIdle { width.value = 360.dp }
        checkRows(3)
    }

    @Test fun overflowingPinsStartAtTheTopAndScrollToTheLastRow() {
        val icon = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val apps = (1..60).map { AppShortcut("test/$it", "Pin $it", icon, android.content.Intent()) }
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                apps = { open, remove ->
                    au.com.michaelcarey.artouchpad.ui.PinnedApps(apps, apps.map { it.id }.toSet(), open, remove, {})
                })
        }
        compose.onNodeWithText("Add App").assertIsDisplayed()
        val lastPin = compose.onNodeWithText("Pin 60")
        lastPin.assertIsNotDisplayed()
        lastPin.performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add App").performScrollTo().assertIsDisplayed()
    }

    @Test fun launcherBoundsFollowTheOnScreenKeyboard() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val previous = shell("settings get secure show_ime_with_hard_keyboard")
        lateinit var root: android.view.View
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.setContent {
                root = androidx.compose.ui.platform.LocalView.current.rootView
                SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                    false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    apps = { open, _ -> androidx.compose.material3.Button(onClick = open) { Text("Choose apps") } },
                    appPicker = { done -> au.com.michaelcarey.artouchpad.ui.AppPicker(emptyList(), emptySet(), done) {} })
            }
            fun panel() = compose.onNodeWithTag("launcher-panel", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val original = panel()
            compose.onNodeWithText("Choose apps").performClick()
            compose.onNodeWithTag("app-search").performClick()
            val ime = android.view.WindowInsets.Type.ime()
            compose.waitUntil(8000) {
                val bottom = root.rootWindowInsets.getInsets(ime).bottom
                bottom > 0 && panel().bottom <= root.height - bottom + 1f
            }
            assertTrue(panel().bottom < original.bottom)
            compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
            compose.waitUntil(8000) { panel() == original }
        } finally {
            if (previous == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $previous")
        }
    }

    @Test fun longAppLabelsHaveOneEllipsizedLine() {
        val label = "TeamViewer Remote Control"
        val shortcut = AppShortcut("test/long", label, android.graphics.Bitmap.createBitmap(10, 10,
            android.graphics.Bitmap.Config.ARGB_8888), android.content.Intent())
        compose.setContent {
            au.com.michaelcarey.artouchpad.ui.PinnedApps(listOf(shortcut), setOf(shortcut.id), {}, {}, {})
        }
        compose.onNodeWithText("Pinned apps").assertDoesNotExist()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { getLayout ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(getLayout(layouts))
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
        }
    }

    @Test fun fourFingerTapClosesPhoneSettingsOnceWithoutDoneButton() {
        var dismissals = 0
        mediaPanel(360, enabled = false, onDismiss = { dismissals++ })
        compose.onNodeWithTag("settings-header").performTouchInput {
            for (finger in 0..3) down(finger, androidx.compose.ui.geometry.Offset(30f + finger * 60f, 20f))
            advanceEventTime(80)
            for (finger in 3 downTo 0) up(finger)
        }
        compose.runOnIdle { assertEquals(1, dismissals) }
        compose.onNodeWithText("Done").assertDoesNotExist()
    }
    @Test fun speedSliderPreviewsContinuouslyButCommitsOnceOnRelease() {
        var changes = 0; var saves = 0
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, { changes++ }, {}, {}, {}, {}, {}, {}, {}, {}, {},
                { saves++ }, {})
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Controls").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))[0].performScrollTo().performTouchInput {
            down(center); moveTo(centerRight - androidx.compose.ui.geometry.Offset(12f, 0f), 100); up()
        }
        compose.runOnIdle { assertTrue(changes > 0); assertEquals(1, saves) }
    }
    @Test fun phoneBackdropDismissesOnlyTheTopModal() {
        var dismissals = 0
        mediaPanel(360, onDismiss = { dismissals++ })
        compose.onNodeWithContentDescription("Information").performClick()
        fun tapBackdrop() = compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(3f, 60f)) }
        tapBackdrop()
        compose.onNodeWithContentDescription("Close information").assertDoesNotExist()
        compose.onNodeWithContentDescription("Information").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissals) }
        tapBackdrop()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun informationPanelExplainsFourFingerSettingsWithoutEdgeSwipe() {
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithContentDescription("Information").performClick()
        compose.onNodeWithText("Four-finger tap", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Open / close Launchpad", useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasText("Settings fallback", substring = true)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Close information").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithContentDescription("Information").assertExists()
    }
}
