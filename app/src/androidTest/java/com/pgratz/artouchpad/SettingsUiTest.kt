package com.pgratz.artouchpad

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pgratz.artouchpad.ui.SettingsPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule val compose = createComposeRule()
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
        compose.onAllNodes(hasText("Settings fallback", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Done").assertExists()
    }
}
