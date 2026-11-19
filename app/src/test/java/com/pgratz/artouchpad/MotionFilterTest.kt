package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class MotionFilterTest {
    @Test fun sameVelocityAtDifferentRatesHasSamePositionAndEndpoint() {
        for (rate in listOf(60, 90, 120)) {
            val f = MotionFilter(); f.reset(0)
            var x = 0f; var y = 0f; var last = 0L
            for (i in 1..rate) {
                val time = i * 1000L / rate
                val delta = (time - last).toFloat(); last = time
                val p = f.move(delta, -delta / 2, time, Smoothing.STEADY)
                x += p.first; y += p.second
            }
            assertEquals(976f, x, 0.01f)
            val tail = f.finish()
            assertEquals(1000f, x + tail.first, 0.01f)
            assertEquals(-500f, y + tail.second, 0.01f)
            assertEquals(0f to 0f, f.finish())
        }
    }
    @Test fun shortMovementAndReversalPreserveDistance() {
        val f = MotionFilter(); f.reset(0)
        val a = f.move(2f, -3f, 8, Smoothing.LIGHT)
        val b = f.move(-1f, 2f, 16, Smoothing.LIGHT)
        val tail = f.finish()
        assertEquals(1f, a.first + b.first + tail.first, 0.0001f)
        assertEquals(-1f, a.second + b.second + tail.second, 0.0001f)
    }
    @Test fun offIsExactAndCancellationDropsTheTail() {
        val f = MotionFilter()
        assertEquals(2f to -3f, f.move(2f, -3f, 0, Smoothing.OFF))
        f.move(10f, 1f, 8, Smoothing.STEADY); f.reset()
        assertEquals(0f to 0f, f.finish())
    }
    @Test fun badInputCannotPoisonFutureMovement() {
        val f = MotionFilter()
        assertEquals(0f to 0f, f.move(Float.NaN, 1f, 0, Smoothing.LIGHT))
        assertEquals(2f to 3f, f.move(2f, 3f, 8, Smoothing.OFF))
    }
}
