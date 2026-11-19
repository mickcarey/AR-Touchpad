package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class ScrollSchedulerTest {
    private var time = 0L
    private val tasks = mutableMapOf<Runnable, Long>()
    private val sent = mutableListOf<Triple<Long, Float, Float>>()
    private val scheduler = ScrollScheduler({ time }, { r, delay -> tasks[r] = time + delay },
        { tasks.remove(it); Unit }, { x, y -> sent += Triple(time, x, y) })
    private fun advance(to: Long) {
        while (tasks.values.minOrNull()?.let { it <= to } == true) {
            time = tasks.values.min()
            val due = tasks.filterValues { it == time }.keys.toList()
            due.forEach { tasks.remove(it); it.run() }
        }
        time = to
    }
    @Test fun stoppedFingerDrainsWithoutLiftAndPreservesBothAxes() {
        scheduler.add(10f, -20f); advance(200)
        assertEquals(10f, sent.sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
        assertEquals(-20f, sent.sumOf { it.third.toDouble() }.toFloat(), 0.0001f)
        assertTrue(tasks.isEmpty()); assertTrue(sent.last().first <= 112)
    }
    @Test fun ninetyHzSamplesStillProduceScheduledSixtyHzDelivery() {
        for (i in 0..89) { advance(i * 1000L / 90); scheduler.add(0f, 1f) }
        assertTrue(sent.size in 60..63)
        advance(1200); assertEquals(90f, sent.sumOf { it.third.toDouble() }.toFloat(), 0.0001f)
    }
    @Test fun finishKeepsSmoothTailRatherThanDumpingItAtLift() {
        scheduler.add(100f, 0f); val count = sent.size; scheduler.finish()
        assertEquals(count, sent.size); advance(16)
        assertTrue(sent.last().second < 60f); advance(200); assertTrue(tasks.isEmpty())
    }
    @Test fun flushOrdersRemainderBeforeButtonAndNeverSendsAgain() {
        scheduler.add(10f, 20f); scheduler.flush(); val count = sent.size; advance(200)
        assertEquals(count, sent.size); assertTrue(tasks.isEmpty())
        assertEquals(10f, sent.sumOf { it.second.toDouble() }.toFloat(), 0f)
    }
    @Test fun reconnectDiscardsOldTailAndStartsImmediately() {
        scheduler.add(10f, 0f); scheduler.reset(); advance(8); scheduler.add(-1f, 0f)
        assertEquals(8, sent.last().first); advance(200)
        assertEquals(3f, sent.sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
    }
    @Test fun nonFiniteAndZeroInputsCannotScheduleAnInfiniteTail() {
        scheduler.add(Float.NaN, 1f); scheduler.add(0f, Float.POSITIVE_INFINITY); scheduler.add(0f, 0f)
        assertTrue(tasks.isEmpty()); assertTrue(sent.isEmpty())
    }
}
