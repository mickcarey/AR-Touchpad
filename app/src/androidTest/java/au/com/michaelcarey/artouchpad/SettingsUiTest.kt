package au.com.michaelcarey.artouchpad

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
            compose.onNode(hasText("Debug") and inHeader).assertIsDisplayed()
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
    @Test fun wideOverviewUsesEqualColumnsAndCenteredTabs() {
        mediaPanel(1000)
        val left = compose.onNodeWithTag("apps-pane").getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag("clock-pane").getUnclippedBoundsInRoot()
        assertEquals((left.right - left.left).value, (right.right - right.left).value, 1f)
        assertTrue(left.right < right.left)
        val header = compose.onNodeWithTag("settings-header").getUnclippedBoundsInRoot()
        val overview = compose.onNodeWithText("Overview").getUnclippedBoundsInRoot()
        val library = compose.onNodeWithText("App library").getUnclippedBoundsInRoot()
        assertEquals((header.left.value + header.right.value) / 2, (overview.left.value + library.right.value) / 2, 1f)
        val title = compose.onNodeWithText("Launchpad").getUnclippedBoundsInRoot()
        val info = compose.onNodeWithContentDescription("Information").getUnclippedBoundsInRoot()
        val cog = compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        assertTrue(title.right <= info.left && info.right <= cog.left)
        compose.onNodeWithText("App library").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Personalise").assertIsSelected()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("App library").assertIsSelected()
    }
    @Test fun wideMediaHeaderStaysVisibleAndSendsPreviousNextAndMute() = verifyPinnedMedia(1000)
    @Test fun overviewAndSettingsAreSeparateAndTitleIsOptional() {
        val visible = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                mediaEnabled = true, mediaState = MediaState(title = "Night train", subtitle = "Kyoto"),
                showTitle = visible.value, apps = { _, _ -> Text("Pinned apps") }, appearance = { Text("Appearance") })
        }
        compose.onNodeWithText("Pinned apps").assertIsDisplayed()
        compose.onNodeWithText("Appearance").assertDoesNotExist()
        compose.onNodeWithText("Night train · Kyoto").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("Night train · Kyoto").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("Pinned apps").assertDoesNotExist()
    }
    @Test fun disconnectedMediaControlsStayVisibleButDisabled() {
        mediaPanel(360, enabled = false)
        compose.onNodeWithContentDescription("Switch to Prod").assertDoesNotExist()
        if (TouchpadBuild.current == TouchpadBuild.Debug) compose.onNodeWithText("Debug").assertIsDisplayed()
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
    @Test fun canPinAndRemoveAnInstalledLauncherActivity() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val app = AppLauncher(InstalledApps).catalog(context).groupBy { it.label }.values.first { it.size == 1 }.single()
        val pins = androidx.compose.runtime.mutableStateOf(emptySet<String>())
        val library = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                au.com.michaelcarey.artouchpad.ui.PinnedApps(pins.value, null, library.value, { library.value = true },
                    { pins.value = it }, {})
            }
        }
        compose.onNodeWithText("Add App").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(app.label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.label).performScrollTo().performClick()
        compose.runOnIdle { library.value = false }
        compose.onNodeWithText(app.label).assertExists()
        compose.runOnIdle { assertEquals(setOf(app.id), pins.value) }
        compose.onNodeWithText("Add App").performScrollTo().performClick()
        compose.onNodeWithText(app.label).performScrollTo().performClick()
        compose.runOnIdle { library.value = false }
        compose.onNodeWithText(app.label).assertDoesNotExist()
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
    @Test fun informationPanelExplainsFourFingerSettingsWithoutEdgeSwipe() {
        compose.setContent {
            SettingsPanel(0.5f, 0.8f, true, AxisRotation.NONE, null, false, {}, 30,
                false, false, false, false, emptyList(), null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithContentDescription("Information").performClick()
        compose.onNodeWithText("Four-finger tap", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Open / close Launchpad", useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasText("Settings fallback", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithContentDescription("Information").assertExists()
    }
}
