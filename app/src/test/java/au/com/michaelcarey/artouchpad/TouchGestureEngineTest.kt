package au.com.michaelcarey.artouchpad

import org.junit.Assert.*
import org.junit.Test

class TouchGestureEngineTest {
    private class Recorder : TouchGestureEngine.Output {
        val actions = mutableListOf<String>()
        var x = 0f; var y = 0f; var scrollY = 0f; var pinch = 0f
        override fun move(dx: Float, dy: Float, time: Long) { x += dx; y += dy; actions += "move" }
        override fun click() { actions += "click" }
        override fun rightClick() { actions += "right" }
        override fun threeFingerTap() { actions += "three" }
        override fun scroll(dx: Float, dy: Float) { scrollY += dy; actions += "scroll" }
        override fun pinch(delta: Float) { pinch += delta; actions += "pinch" }
        override fun mode(mode: TouchMode) { actions += mode.name }
        override fun startDrag() { actions += "down" }
        override fun endDrag() { actions += "end" }
        override fun cancelDrag() { actions += "cancel" }
        override fun dragHeld(held: Boolean) { actions += "held:$held" }
        override fun dragReady(ready: Boolean) { actions += "ready:$ready" }
        override fun settings() { actions += "settings" }
        override fun pocket() { actions += "pocket" }
    }
    private val out = Recorder()
    private fun p(id: Long = 1, x: Float = 400f, y: Float = 400f, down: Boolean = true) =
        MultiFingerTapTracker.Contact(id, x, y, down)
    private fun TouchGestureEngine.at(time: Long, vararg contacts: MultiFingerTapTracker.Contact) =
        event(time, contacts.toList(), 1000f, 1000f)
    private fun engine(lock: Boolean = false, hold: Long = 600) = TouchGestureEngine(out, hold, lock)
    private fun TouchGestureEngine.drag() {
        at(0, p()); onTime(600); at(620, p(x = 420f))
    }

    @Test fun physicalDoubleTapProducesExactlyTwoClicks() {
        val e = engine()
        e.at(0, p()); e.at(50, p(down = false)); e.at(100, p()); e.at(150, p(down = false))
        assertEquals(listOf("click", "click"), out.actions)
    }
    @Test fun slowDragKeepsTravelThatCrossedTheDeadZone() {
        val e = engine(); e.at(0, p())
        for (i in 1..10) e.at(i * 8L, p(x = 400f + i))
        e.at(90, p(x = 410f, down = false))
        assertEquals(10f, out.x, 0.001f)
        assertFalse(out.actions.contains("click"))
    }
    @Test fun longHoldOnlySignalsReadinessUntilMovement() {
        val e = engine(); e.at(0, p()); e.onTime(599)
        assertTrue(out.actions.isEmpty()); e.onTime(600)
        assertEquals(listOf("ready:true"), out.actions)
        e.at(700, p(down = false))
        assertEquals("right", out.actions.last())
        assertFalse(out.actions.contains("down"))
    }
    @Test fun dragFlushesBeforeEndingAndDoesNotClick() {
        val e = engine(); e.drag(); e.at(700, p(x = 420f, down = false))
        assertTrue(out.actions.indexOf("down") < out.actions.indexOf("move"))
        assertTrue(out.actions.indexOf("IDLE") < out.actions.indexOf("end"))
        assertEquals(1, out.actions.count { it == "end" })
        assertFalse(out.actions.contains("click"))
    }
    @Test fun secondFingerCancelsDragWithoutCompletionOrCopy() {
        val e = engine(); e.drag()
        e.at(640, p(x = 420f), p(2, 600f))
        e.at(660, p(x = 420f, down = false), p(2, 600f, down = false))
        assertEquals(1, out.actions.count { it == "cancel" })
        assertFalse(out.actions.contains("end")); assertFalse(out.actions.contains("right"))
    }
    @Test fun systemCancellationReleasesDragExactlyOnce() {
        val e = engine(); e.drag(); e.cancel(); e.cancel()
        assertEquals(1, out.actions.count { it == "cancel" }); assertFalse(out.actions.contains("end"))
        assertNull(e.nextDeadline)
    }
    @Test fun lifecycleCancellationDropsTrailingContactsAndAllowsNextFreshGesture() {
        val e = engine(lock = true); e.drag(); e.invalidate(); e.invalidate()
        e.at(650, p(x = 450f)); e.at(700, p(x = 450f, down = false))
        assertEquals(1, out.actions.count { it == "cancel" })
        assertFalse(out.actions.contains("end")); assertFalse(out.actions.contains("click"))
        e.at(800, p()); e.at(850, p(down = false))
        assertEquals(1, out.actions.count { it == "click" })
    }
    @Test fun dragLockSurvivesLiftAndRepositionUntilTap() {
        val e = engine(lock = true); e.drag(); e.at(700, p(x = 420f, down = false))
        assertTrue(out.actions.contains("held:true")); assertFalse(out.actions.contains("end"))
        e.at(800, p(x = 300f)); e.at(820, p(x = 330f)); e.at(850, p(x = 330f, down = false))
        assertFalse(out.actions.contains("end"))
        e.at(900, p()); e.at(950, p(down = false))
        assertEquals(1, out.actions.count { it == "down" })
        assertEquals(1, out.actions.count { it == "end" })
        assertFalse(out.actions.contains("click"))
    }
    @Test fun lockingInputReleasesAnIntentionallyHeldButton() {
        val e = engine(lock = true); e.drag(); e.at(700, p(x = 420f, down = false)); e.setInputAllowed(false)
        assertTrue(out.actions.contains("cancel")); assertEquals("held:false", out.actions.last())
    }
    @Test fun twoFingerTapWaitsForLastReleaseAndDoesNotLeftClick() {
        val e = engine(); e.at(0, p()); e.at(20, p(), p(2, 600f))
        e.at(50, p(down = false), p(2, 600f)); e.at(70, p(2, 600f, down = false))
        assertEquals(listOf("right"), out.actions)
    }
    @Test fun scrollLatchesAndTrailingFingerCannotBecomeCursorOrTap() {
        val e = engine(); e.at(0, p(), p(2, 600f))
        e.at(20, p(y = 420f), p(2, 600f, 420f))
        e.at(30, p(y = 420f, down = false), p(2, 600f, 420f))
        e.at(40, p(2, 650f, 440f)); e.at(50, p(2, 650f, 440f, false))
        assertEquals(20f, out.scrollY, 0f); assertEquals(0f, out.x, 0f)
        assertFalse(out.actions.any { it in listOf("click", "right", "pinch") })
    }
    @Test fun pinchNeverBecomesScrollAfterLatching() {
        val e = engine(); e.at(0, p(), p(2, 600f))
        e.at(20, p(x = 380f), p(2, 620f)); e.at(40, p(x = 380f, y = 440f), p(2, 620f, 440f))
        e.at(60, p(x = 380f, y = 440f, down = false), p(2, 620f, 440f, false))
        assertEquals(40f, out.pinch, 0.001f); assertEquals(0f, out.scrollY, 0f)
        assertFalse(out.actions.contains("right"))
    }
    @Test fun threeFingerTapOnlyCallsMappedShortcut() {
        val e = engine(); e.at(0, p(), p(2, 500f), p(3, 600f))
        e.at(60, p(down = false), p(2, 500f, down = false), p(3, 600f, down = false))
        assertEquals(listOf("three"), out.actions)
    }
    @Test fun fourFingerTapWorksWhilePocketLocked() {
        val e = engine(); e.setInputAllowed(false)
        e.at(0, p(), p(2, 500f), p(3, 600f), p(4, 700f))
        e.at(60, p(down = false), p(2, 500f, down = false), p(3, 600f, down = false), p(4, 700f, down = false))
        assertEquals(listOf("settings"), out.actions)
    }
    @Test fun motionlessFourFingerHoldWorksWhileLockedAndFiresOnce() {
        val e = engine(); e.setInputAllowed(false)
        e.at(0, p(), p(2, 500f), p(3, 600f), p(4, 700f)); e.onTime(800); e.onTime(900)
        assertEquals(listOf("pocket"), out.actions)
    }
    @Test fun edgeSwipesMoveCursorAndNeverOpenSettings() {
        val e = engine()
        e.at(0, p(x = 30f, y = 10f)); e.at(200, p(x = 800f, y = 10f))
        e.at(300, p(x = 800f, y = 10f, down = false))
        assertEquals(770f, out.x, 0f)
        assertFalse(out.actions.contains("settings"))
    }
    @Test fun edgeSwipeWhilePocketLockedDoesNothing() {
        val e = engine(); e.setInputAllowed(false)
        e.at(0, p(x = 30f, y = 10f)); e.at(200, p(x = 800f, y = 10f))
        e.at(300, p(x = 800f, y = 10f, down = false))
        assertTrue(out.actions.isEmpty())
    }
    @Test fun configuredHoldChangesDragDelayButNotPocketDelay() {
        val e = engine(hold = 300); e.at(0, p()); e.onTime(300)
        assertTrue(out.actions.contains("ready:true")); e.cancel(); out.actions.clear()
        e.at(1000, p(), p(2, 500f), p(3, 600f), p(4, 700f)); e.onTime(1300)
        assertFalse(out.actions.contains("pocket")); e.onTime(1800); assertTrue(out.actions.contains("pocket"))
    }
}
