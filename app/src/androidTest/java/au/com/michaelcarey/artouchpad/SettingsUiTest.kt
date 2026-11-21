package au.com.michaelcarey.artouchpad

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
    @Test fun wideMediaHeaderStaysVisibleAndSendsPreviousNextAndMute() = verifyPinnedMedia(1000)
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
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))[0].performTouchInput {
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
        compose.onNodeWithText("Open / close settings", useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasText("Settings fallback", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithContentDescription("Information").assertExists()
    }
}
