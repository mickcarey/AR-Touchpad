package com.pgratz.artouchpad

import com.pgratz.artouchpad.FourFingerGesture.Decision.*
import org.junit.Assert.*
import org.junit.Test

class FourFingerGestureTest {
    private val gesture = FourFingerGesture(slop = 16f)
    private fun contacts(count: Int = 4, pressed: Boolean = true, dx: Float = 0f) =
        (1L..count.toLong()).map { MultiFingerTapTracker.Contact(it, it * 30f + dx, 100f, pressed) }

    @Test fun simultaneousTapOpensSettingsOnlyOnRelease() {
        assertEquals(CONSUMED, gesture.update(0, contacts()))
        assertEquals(OPEN_SETTINGS, gesture.update(100, contacts(pressed = false)))
        assertEquals(PASS, gesture.onTime(1000))
    }

    @Test fun staggeredArrivalAndReleaseProduceOneSettingsAction() {
        for (n in 1..3) assertEquals(PASS, gesture.update(n * 30L, contacts(n)))
        assertEquals(CONSUMED, gesture.update(120, contacts()))
        for (n in 4 downTo 2) {
            val event = contacts(n).map { if (it.id == n.toLong()) it.copy(pressed = false) else it }
            assertEquals(CONSUMED, gesture.update(250L + (4 - n) * 10, event))
        }
        assertEquals(OPEN_SETTINGS, gesture.update(300, contacts(1, pressed = false)))
    }

    @Test fun motionlessHoldUsesTimerAndFiresOnlyOnce() {
        gesture.update(0, contacts())
        assertEquals(800L, gesture.holdDeadline)
        assertEquals(PASS, gesture.onTime(799))
        assertEquals(TOGGLE_POCKET, gesture.onTime(800))
        assertEquals(PASS, gesture.onTime(1600))
        assertEquals(CONSUMED, gesture.update(1700, contacts(pressed = false)))
    }

    @Test fun holdStartsWhenFourthFingerArrives() {
        gesture.update(0, contacts(1))
        gesture.update(150, contacts())
        assertEquals(950L, gesture.holdDeadline)
        assertEquals(PASS, gesture.onTime(800))
        assertEquals(TOGGLE_POCKET, gesture.onTime(950))
    }

    @Test fun releaseBeforeHoldCancelsTimerAndDoesNotOpenSettings() {
        gesture.update(0, contacts())
        assertEquals(CONSUMED, gesture.update(500, contacts(pressed = false)))
        assertNull(gesture.holdDeadline)
        assertEquals(PASS, gesture.onTime(800))
    }

    @Test fun liftingOneFingerCancelsHoldIncludingTrailingReleases() {
        gesture.update(0, contacts())
        assertEquals(CONSUMED, gesture.update(400, contacts().map {
            if (it.id == 4L) it.copy(pressed = false) else it
        }))
        assertNull(gesture.holdDeadline)
        assertEquals(PASS, gesture.onTime(1000))
        assertEquals(CONSUMED, gesture.update(1100, contacts(3, pressed = false)))
    }

    @Test fun movementOutAndBackNeverUnlocksOrOpensSettings() {
        gesture.update(0, contacts())
        gesture.update(100, contacts(dx = 20f))
        gesture.update(150, contacts())
        assertNull(gesture.holdDeadline)
        assertEquals(PASS, gesture.onTime(1000))
        assertEquals(CONSUMED, gesture.update(1100, contacts(pressed = false)))
    }

    @Test fun fifthFingerCancelsEvenAfterItLifts() {
        gesture.update(0, contacts())
        gesture.update(100, contacts(5))
        gesture.update(150, contacts(5).map { if (it.id == 5L) it.copy(pressed = false) else it })
        assertNull(gesture.holdDeadline)
        assertEquals(PASS, gesture.onTime(1000))
        assertEquals(CONSUMED, gesture.update(1100, contacts(pressed = false)))
    }

    @Test fun fourthFingerArrivingLateCannotTurnExistingGestureIntoShortcut() {
        gesture.update(0, contacts(3))
        assertEquals(CONSUMED, gesture.update(201, contacts()))
        assertNull(gesture.holdDeadline)
        assertEquals(CONSUMED, gesture.update(300, contacts(pressed = false)))
    }

    @Test fun oneTwoAndThreeFingerGesturesPassThrough() {
        for (count in 1..3) {
            assertEquals(PASS, gesture.update(1000L * count, contacts(count)))
            assertEquals(PASS, gesture.update(1000L * count + 100, contacts(count, pressed = false)))
        }
    }

    @Test fun consumedFourFingerSequenceDoesNotPoisonNextThreeFingerTap() {
        val multi = MultiFingerTapTracker()
        fun update(time: Long, count: Int, down: Boolean): Pair<FourFingerGesture.Decision, Int?> {
            val points = contacts(count, down)
            val tap = multi.update(time, points)
            val result = gesture.update(time, points)
            if (result != PASS) multi.cancelTap()
            return result to tap
        }
        update(0, 3, true)
        update(30, 4, true)
        assertEquals(OPEN_SETTINGS, update(100, 4, false).first)
        update(1000, 3, true)
        assertEquals(PASS to 3, update(1100, 3, false))
    }

    @Test fun cancellationStopsTimerAndFreshGestureCanUnlock() {
        gesture.update(0, contacts())
        gesture.cancel()
        assertEquals(PASS, gesture.onTime(1000))
        gesture.update(1100, contacts(pressed = false))
        gesture.update(2000, contacts())
        assertEquals(TOGGLE_POCKET, gesture.onTime(2800))
    }
}
