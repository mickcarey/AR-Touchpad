package com.pgratz.artouchpad

import com.pgratz.artouchpad.SettingsEdgeSwipe.Decision.*
import org.junit.Assert.*
import org.junit.Test

class SettingsEdgeSwipeTest {
    private val swipe = SettingsEdgeSwipe(edgeWidth = 56f, minimumTravel = 72f, crossSlop = 36f)
    private fun start(x: Float = 350f, y: Float = 780f, width: Float = 400f) = swipe.begin(x, y, width, 800f, 0)
    private fun move(x: Float, y: Float = 780f, time: Long = 100, count: Int = 1, up: Boolean = false) =
        swipe.update(x, y, time, count, up)

    @Test fun bothPortraitEdgesWorkInBothDirections() {
        for (y in listOf(20f, 780f)) {
            assertEquals(PENDING, start(350f, y))
            assertEquals(CONSUMED, move(250f, y))
            assertEquals(OPEN_SETTINGS, move(50f, y, time = 900, count = 0, up = true))
            assertEquals(PENDING, start(50f, y))
            assertEquals(CONSUMED, move(150f, y))
            assertEquals(OPEN_SETTINGS, move(350f, y, time = 900, count = 0, up = true))
        }
    }

    @Test fun mostOfWidthIsRequiredRatherThanJustShortEdgeFlick() {
        start()
        assertEquals(CONSUMED, move(250f))
        assertEquals(CONSUMED, move(100f, count = 0, up = true)) // 250 < 65% of 400
        start()
        assertEquals(OPEN_SETTINGS, move(90f, count = 0, up = true))
    }

    @Test fun distanceScalesWithPhoneWidth() {
        start(x = 750f, width = 800f)
        move(650f)
        assertEquals(CONSUMED, move(350f, count = 0, up = true))
        start(x = 750f, width = 800f)
        move(650f)
        assertEquals(OPEN_SETTINGS, move(200f, count = 0, up = true))
    }

    @Test fun ordinaryMovementAndPortraitSideEdgesRemainMouseInput() {
        assertEquals(TRACKPAD, start(y = 400f))
        assertEquals(TRACKPAD, move(50f, y = 400f, count = 0, up = true))
        assertEquals(TRACKPAD, start(x = 10f, y = 400f))
        assertEquals(TRACKPAD, move(10f, y = 700f))
        assertEquals(TRACKPAD, start(x = 390f, y = 400f))
        assertEquals(TRACKPAD, move(390f, y = 700f))
    }

    @Test fun edgeTapAndStationaryLongPressReturnToMouse() {
        start()
        assertEquals(TRACKPAD, move(350f, up = true, count = 0))
        start()
        assertEquals(TRACKPAD, move(350f, time = 700, up = true, count = 0))
    }

    @Test fun shortUnclaimedSwipeReturnsToMouse() {
        start()
        assertEquals(TRACKPAD, move(300f, up = true, count = 0))
    }

    @Test fun modestDriftIsAllowedButDiagonalMovementRejectsPermanently() {
        start(y = 30f)
        move(250f, y = 50f)
        assertEquals(OPEN_SETTINGS, move(50f, y = 60f, count = 0, up = true))
        start()
        assertEquals(TRACKPAD, move(330f, y = 730f))
        assertEquals(TRACKPAD, move(50f, up = true, count = 0))
    }

    @Test fun multiFingerGesturesAreNotSettingsSwipes() {
        start()
        assertEquals(TRACKPAD, move(330f, count = 2))
        assertEquals(TRACKPAD, move(50f, count = 1))
        assertEquals(TRACKPAD, move(50f, up = true, count = 0))
    }

    @Test fun extraFingerAfterClaimCancelsWithoutLeakingMouseEvents() {
        start()
        assertEquals(CONSUMED, move(250f))
        assertEquals(CONSUMED, move(200f, count = 2))
        assertEquals(CONSUMED, move(50f, count = 0, up = true))
    }

    @Test fun returningToStartDoesNotOpenSettings() {
        start()
        move(50f)
        assertEquals(CONSUMED, move(340f, count = 0, up = true))
    }

    @Test fun reversingPastStartAfterClaimCancels() {
        start(x = 100f)
        move(200f)
        move(50f)
        assertEquals(CONSUMED, move(390f, count = 0, up = true))
    }

    @Test fun longPressThenMovementCannotOpenSettings() {
        start()
        assertEquals(TRACKPAD, move(250f, time = 700))
        assertEquals(TRACKPAD, move(50f, time = 800, count = 0, up = true))
    }

    @Test fun relaxedSweepCanTakeMoreThanTwoSecondsAfterClaim() {
        start()
        move(250f, time = 400)
        assertEquals(OPEN_SETTINGS, move(50f, time = 2500, count = 0, up = true))
    }

    @Test fun claimedGestureExpiresAndNextGestureStartsClean() {
        start()
        move(250f)
        assertEquals(CONSUMED, move(50f, time = 3100, count = 0, up = true))
        assertEquals(PENDING, start())
        assertEquals(OPEN_SETTINGS, move(50f, up = true, count = 0))
        assertEquals(TRACKPAD, move(50f, up = true, count = 0))
    }
}
