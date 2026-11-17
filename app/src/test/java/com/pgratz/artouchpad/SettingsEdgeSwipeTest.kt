package com.pgratz.artouchpad

import com.pgratz.artouchpad.SettingsEdgeSwipe.Decision.*
import org.junit.Assert.*
import org.junit.Test

class SettingsEdgeSwipeTest {
    private val swipe = SettingsEdgeSwipe(edgeWidth = 48f, minimumTravel = 72f, crossSlop = 28f)
    private fun start(x: Float = 350f, y: Float = 780f) = swipe.begin(x, y, 400f, 800f, 0)
    private fun move(x: Float, y: Float = 780f, time: Long = 100, count: Int = 1, up: Boolean = false) =
        swipe.update(x, y, time, count, up)

    @Test fun rotatedRightEdgeDownIsPortraitBottomEdgeLeft() {
        assertEquals(PENDING, start())
        assertEquals(PENDING, move(310f))
        assertEquals(CONSUMED, move(260f))
        assertEquals(OPEN_SETTINGS, move(100f, time = 700, count = 0, up = true))
        assertEquals(TRACKPAD, move(100f, count = 0, up = true))
    }

    @Test fun ordinaryMovementAndOtherEdgesRemainMouseInput() {
        assertEquals(TRACKPAD, start(y = 400f))
        assertEquals(TRACKPAD, move(200f, y = 400f))
        assertEquals(TRACKPAD, start(x = 395f, y = 400f))
        assertEquals(TRACKPAD, move(395f, y = 600f))
    }

    @Test fun edgeTapAndStationaryLongPressReturnToMouse() {
        start()
        assertEquals(TRACKPAD, move(350f, up = true, count = 0))
        start()
        assertEquals(TRACKPAD, move(350f, time = 700, up = true, count = 0))
    }

    @Test fun oppositeDirectionOrCrossEdgeMotionRejectsPermanently() {
        start(x = 150f)
        assertEquals(TRACKPAD, move(200f))
        assertEquals(TRACKPAD, move(50f, up = true, count = 0))
        start()
        assertEquals(TRACKPAD, move(330f, y = 730f))
        assertEquals(TRACKPAD, move(200f, up = true, count = 0))
    }

    @Test fun multiFingerGesturesAreNotSettingsSwipes() {
        start()
        assertEquals(TRACKPAD, move(330f, count = 2))
        assertEquals(TRACKPAD, move(200f, count = 1))
        assertEquals(TRACKPAD, move(200f, up = true, count = 0))
    }

    @Test fun extraFingerAfterClaimCancelsWithoutLeakingMouseEvents() {
        start()
        assertEquals(CONSUMED, move(250f))
        assertEquals(CONSUMED, move(200f, count = 2))
        assertEquals(CONSUMED, move(100f, count = 0, up = true))
    }

    @Test fun shortSwipeReturnsToMouseAndReverseAfterClaimDoesNotOpen() {
        start()
        assertEquals(TRACKPAD, move(300f, up = true, count = 0))
        start()
        move(250f)
        assertEquals(CONSUMED, move(340f, count = 0, up = true))
    }

    @Test fun longPressThenMovementCannotOpenSettings() {
        start()
        assertEquals(TRACKPAD, move(200f, time = 700))
        assertEquals(TRACKPAD, move(100f, time = 800, count = 0, up = true))
    }

    @Test fun claimedGestureExpiresAndNextGestureStartsClean() {
        start()
        move(250f)
        assertEquals(CONSUMED, move(100f, time = 1800, count = 0, up = true))
        assertEquals(PENDING, start())
        assertEquals(OPEN_SETTINGS, move(200f, up = true, count = 0))
    }
}
