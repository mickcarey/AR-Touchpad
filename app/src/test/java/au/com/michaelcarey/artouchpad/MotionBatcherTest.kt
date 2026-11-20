package au.com.michaelcarey.artouchpad

import org.junit.Assert.*
import org.junit.Test

class MotionBatcherTest {
    private var time = 0L
    private val tasks = mutableMapOf<Runnable, Long>()
    private val sent = mutableListOf<Pair<Float, Float>>()
    private val batcher = MotionBatcher(
        now = { time },
        schedule = { task, delay -> tasks[task] = time + delay },
        cancel = { tasks.remove(it); Unit },
        send = { x, y -> sent.add(x to y); Unit },
    )

    private fun advance(to: Long) {
        time = to
        tasks.filterValues { it <= time }.keys.toList().forEach {
            tasks.remove(it)
            it.run()
        }
    }

    @Test fun stoppedFingerStillDeliversBufferedMotionAtDeadline() {
        batcher.add(1f, 2f)
        advance(8)
        batcher.add(3f, 4f)
        assertEquals(listOf(1f to 2f), sent)
        advance(16)
        assertEquals(listOf(1f to 2f, 3f to 4f), sent)
        assertTrue(tasks.isEmpty())
    }

    @Test fun highFrequencySamplesCoalesceWithoutPostponingDeadline() {
        batcher.add(1f, 0f)
        for (t in 1L..15L) {
            advance(t)
            batcher.add(0.5f, -0.25f)
        }
        assertEquals(listOf(16L), tasks.values.toList())
        advance(16)
        assertEquals(listOf(1f to 0f, 7.5f to -3.75f), sent)
    }

    @Test fun gestureEndOrButtonFlushSendsTailOnceBeforeButton() {
        batcher.add(1f, 0f)
        advance(8)
        batcher.add(2f, 3f)
        batcher.flush()
        assertEquals(listOf(1f to 0f, 2f to 3f), sent)
        assertTrue(tasks.isEmpty())
        advance(100)
        batcher.flush()
        assertEquals(2, sent.size)
    }

    @Test fun reconnectOrDisplayChangeDiscardsOldTailAndDeadline() {
        batcher.add(1f, 0f)
        advance(8)
        batcher.add(20f, 30f)
        batcher.reset()
        assertTrue(tasks.isEmpty())
        batcher.add(-2f, -3f)
        assertEquals(listOf(1f to 0f, -2f to -3f), sent)
        advance(100)
        assertEquals(2, sent.size)
    }

    @Test fun continuous120HzInputPreservesTotalDistanceAndLeavesNoCallbacks() {
        for (t in 0L..800L step 8) {
            advance(t)
            batcher.add(0.25f, -0.5f)
        }
        batcher.flush()
        assertEquals(25.25f, sent.sumOf { it.first.toDouble() }.toFloat(), 0f)
        assertEquals(-50.5f, sent.sumOf { it.second.toDouble() }.toFloat(), 0f)
        assertTrue(tasks.isEmpty())
    }
}
