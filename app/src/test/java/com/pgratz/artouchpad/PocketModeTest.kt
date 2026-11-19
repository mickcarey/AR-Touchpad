package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class PocketModeTest {
    private val ready = TouchpadState(mouseReady = true,
        targetDisplay = DisplayInfo(11, "Glasses", 1920, 1080))

    @Test fun lockingBlocksTouchpadButKeepsConnectionAndBlackPreference() {
        val locked = ready.copy(pocketMode = true, blackTouchpad = true)
        assertFalse(locked.touchpadInputEnabled)
        assertTrue(locked.mouseReady)
        assertTrue(locked.externalDisplayConnected)
        assertTrue(locked.blackTouchpad)
    }

    @Test fun lockedSettingsUseExternalDisplayWhenConnected() {
        assertFalse(ready.settingsRequirePhone)
        assertFalse(ready.copy(pocketMode = true).settingsRequirePhone)
        assertFalse(ready.copy(pocketMode = true, showSettings = true).settingsRequirePhone)
    }

    @Test fun reconnectingDoesNotUnlockPocketMode() {
        val disconnected = ready.copy(pocketMode = true, mouseReady = false, targetDisplay = null)
        assertFalse(disconnected.touchpadInputEnabled)
        val reconnected = disconnected.copy(mouseReady = true, targetDisplay = ready.targetDisplay)
        assertFalse(reconnected.touchpadInputEnabled)
        assertFalse(reconnected.settingsRequirePhone)
    }

    @Test fun settingsTemporarilyEnablesInputThenRelocksWithoutChangingPocketPreference() {
        val locked = ready.copy(pocketMode = true, blackTouchpad = true)
        val settings = locked.copy(showSettings = true)
        assertTrue(settings.pocketMode)
        assertFalse(settings.pocketInputLocked)
        assertTrue(settings.touchpadInputEnabled)
        assertTrue(settings.blackTouchpad)
        val closed = settings.copy(showSettings = false)
        assertTrue(closed.pocketMode)
        assertTrue(closed.pocketInputLocked)
        assertFalse(closed.touchpadInputEnabled)
    }

    @Test fun disablingPocketModeInSettingsStaysUnlockedAfterClosing() {
        val settings = ready.copy(pocketMode = true, showSettings = true)
        val unlocked = settings.copy(pocketMode = false)
        assertTrue(unlocked.touchpadInputEnabled)
        assertTrue(unlocked.copy(showSettings = false).touchpadInputEnabled)
    }

    @Test fun enablingPocketModeInSettingsKeepsControlsUsableUntilClosed() {
        val settings = ready.copy(showSettings = true).copy(pocketMode = true)
        assertTrue(settings.touchpadInputEnabled)
        assertFalse(settings.copy(showSettings = false).touchpadInputEnabled)
    }

    @Test fun disconnectedSettingsKeepsPhoneRecoveryAndNeverSendsMouseInput() {
        val settings = ready.copy(pocketMode = true, showSettings = true, mouseReady = false)
        assertTrue(settings.settingsRequirePhone)
        assertFalse(settings.touchpadInputEnabled)
        assertTrue(settings.copy(targetDisplay = null).settingsRequirePhone)
    }

    @Test fun unlockingOnlyEnablesInputWhenMouseIsReady() {
        assertTrue(ready.copy(pocketMode = false).touchpadInputEnabled)
        assertFalse(ready.copy(pocketMode = false, mouseReady = false).touchpadInputEnabled)
    }

    @Test fun settingsEdgeSwipeIsRecognizedWhileInputIsLocked() {
        val locked = ready.copy(pocketMode = true)
        assertFalse(locked.touchpadInputEnabled)
        val swipe = SettingsEdgeSwipe(56f, 72f, 36f)
        swipe.begin(30f, 20f, 400f, 800f, 0)
        assertEquals(SettingsEdgeSwipe.Decision.CONSUMED, swipe.update(130f, 20f, 200, 1, false))
        assertEquals(SettingsEdgeSwipe.Decision.OPEN_SETTINGS, swipe.update(350f, 20f, 700, 1, true))
    }
}
