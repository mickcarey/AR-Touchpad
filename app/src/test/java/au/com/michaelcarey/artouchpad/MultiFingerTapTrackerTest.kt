package au.com.michaelcarey.artouchpad

import org.junit.Assert.*
import org.junit.Test

class MultiFingerTapTrackerTest {
    private val tracker = MultiFingerTapTracker()
    private fun finger(id: Long, x: Float = 0f, pressed: Boolean = true) =
        MultiFingerTapTracker.Contact(id, x, id * 30f, pressed)

    @Test fun staggeredTwoFingerTapWaitsForFinalRelease() {
        assertNull(tracker.update(0, listOf(finger(1))))
        assertNull(tracker.update(25, listOf(finger(1), finger(2))))
        assertNull(tracker.update(80, listOf(finger(1, pressed = false), finger(2))))
        assertTrue(tracker.releasing)
        assertEquals(2, tracker.maxFingers)
        assertEquals(2, tracker.update(110, listOf(finger(2, pressed = false))))
    }

    @Test fun threeFingerTapDoesNotBecomeRightClickDuringRelease() {
        tracker.update(0, listOf(finger(1)))
        tracker.update(20, listOf(finger(1), finger(2)))
        tracker.update(40, listOf(finger(1), finger(2), finger(3)))
        assertNull(tracker.update(60, listOf(finger(1), finger(2), finger(3, pressed = false))))
        assertEquals(3, tracker.maxFingers)
        assertNull(tracker.update(80, listOf(finger(1), finger(2, pressed = false))))
        assertEquals(3, tracker.update(100, listOf(finger(1, pressed = false))))
    }

    @Test fun longHoldIsNotTap() {
        tracker.update(0, listOf(finger(1), finger(2)))
        assertNull(tracker.update(220, listOf(finger(1, pressed = false), finger(2, pressed = false))))
    }

    @Test fun movementOutAndBackIsNotTap() {
        tracker.update(0, listOf(finger(1), finger(2)))
        tracker.update(50, listOf(finger(1, x = 15f), finger(2, x = 15f)))
        assertNull(tracker.update(100, listOf(finger(1, pressed = false), finger(2, pressed = false))))
    }

    @Test fun movementOnReleaseIsNotTap() {
        tracker.update(0, listOf(finger(1), finger(2)))
        assertNull(tracker.update(100, listOf(finger(1, x = 20f, pressed = false), finger(2, pressed = false))))
    }

    @Test fun pinchWithStationaryCentroidIsNotTap() {
        tracker.update(0, listOf(finger(1), finger(2)))
        tracker.update(50, listOf(finger(1, x = -15f), finger(2, x = 15f)))
        assertNull(tracker.update(100, listOf(finger(1, pressed = false), finger(2, pressed = false))))
    }

    @Test fun latchedScrollCannotBecomeTapOrThreeFingerAction() {
        tracker.update(0, listOf(finger(1), finger(2)))
        tracker.cancelTap()
        tracker.update(50, listOf(finger(1), finger(2), finger(3)))
        assertNull(tracker.update(100, listOf(finger(1, pressed = false), finger(2, pressed = false), finger(3, pressed = false))))
    }

    @Test fun extraFingerIsNotThreeFingerTap() {
        tracker.update(0, (1L..4L).map { finger(it) })
        assertNull(tracker.update(100, (1L..4L).map { finger(it, pressed = false) }))
    }

    @Test fun replacementFingerAfterReleaseCancelsTap() {
        tracker.update(0, listOf(finger(1), finger(2)))
        tracker.update(30, listOf(finger(1, pressed = false), finger(2)))
        tracker.update(50, listOf(finger(2), finger(3)))
        assertNull(tracker.update(100, listOf(finger(2, pressed = false), finger(3, pressed = false))))
    }

    @Test fun singleTapIsLeftToExistingHandlerAndNextGestureStartsFresh() {
        tracker.update(0, listOf(finger(1)))
        assertNull(tracker.update(50, listOf(finger(1, pressed = false))))
        tracker.update(300, listOf(finger(2), finger(3)))
        assertEquals(2, tracker.update(400, listOf(finger(2, pressed = false), finger(3, pressed = false))))
        tracker.update(500, listOf(finger(4)))
        assertEquals(1, tracker.maxFingers)
        assertFalse(tracker.releasing)
    }
}
