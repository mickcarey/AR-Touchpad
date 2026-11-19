package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class InputSettingsTest {
    @Test fun defaultsDoNotCopyOrHoldMouseButtons() {
        val settings = InputSettings()
        assertFalse(settings.autoCopy); assertFalse(settings.dragLock)
        assertEquals(600L, settings.holdMs)
    }
    @Test fun corruptAndOutOfRangePreferencesAreNormalized() {
        val s = InputSettings(Float.NaN, Float.POSITIVE_INFINITY, holdMs = -1).validated()
        assertEquals(0.5f, s.sensitivity, 0f); assertEquals(0.8f, s.scrollSpeed, 0f); assertEquals(300L, s.holdMs)
        val extreme = InputSettings(100f, -1f, holdMs = Long.MAX_VALUE).validated()
        assertEquals(3f, extreme.sensitivity, 0f); assertEquals(0.1f, extreme.scrollSpeed, 0f)
        assertEquals(1200L, extreme.holdMs)
    }
    @Test fun presetsHaveDistinctUsefulBehaviors() {
        assertTrue(InputSettings.preset(UsageProfile.DESKTOP).phoneKeyboard)
        assertEquals(Smoothing.OFF, InputSettings.preset(UsageProfile.MOONLIGHT).smoothing)
        val movie = InputSettings.preset(UsageProfile.MOVIE)
        assertTrue(movie.blackTouchpad); assertEquals(ThreeFingerAction.PLAY_PAUSE, movie.threeFingerAction)
    }
    @Test fun profileChangesNeverRestoreRuntimeStateOrEraseCalibration() {
        val state = TouchpadState(pocketMode = true, showSettings = true, mouseReady = false,
            calibratedRotationDegrees = 32f)
        val applied = state.withInputSettings(InputSettings.preset(UsageProfile.DESKTOP))
        assertTrue(applied.pocketMode); assertTrue(applied.showSettings); assertFalse(applied.mouseReady)
        assertEquals(32f, applied.calibratedRotationDegrees!!, 0f)
        assertEquals(InputSettings.preset(UsageProfile.DESKTOP), applied.inputSettings())
    }
    @Test fun preferredDisplaySurvivesChangingNumericIdsAndFallsBackWhenMissing() {
        val phone = DisplayInfo(0, "Phone", 100, 200, "phone")
        val other = DisplayInfo(9, "Other", 200, 100, "other")
        val glasses = DisplayInfo(15, "Glasses", 200, 100, "glasses")
        assertEquals(glasses, selectDisplay(listOf(phone, other, glasses), "glasses"))
        val replugged = glasses.copy(id = 20)
        assertEquals(replugged, selectDisplay(listOf(phone, other, replugged), "glasses"))
        assertEquals(other, selectDisplay(listOf(phone, other), "glasses"))
        assertNull(selectDisplay(listOf(phone), "phone"))
    }
}
