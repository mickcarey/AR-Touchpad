package au.com.michaelcarey.artouchpad

import kotlin.math.abs

/** Scheduled scroll delivery continues through a stationary finger, with a bounded tail.
 * finish drains smoothly; flush orders the remainder before a button/shortcut; reset discards it.
 */
internal class ScrollScheduler(
    private val now: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (Float, Float) -> Unit,
) {
    private var x = 0f
    private var y = 0f
    private var scheduled = false
    private var lastInput = 0L
    private var lastSent: Long? = null
    private val tick = Runnable { scheduled = false; deliver() }

    fun add(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return
        x += dx; y += dy; lastInput = now()
        val wait = lastSent?.let { (16 - (now() - it)).coerceAtLeast(0) } ?: 0
        if (!scheduled) {
            if (wait == 0L) deliver() else enqueue(wait)
        }
    }

    private fun enqueue(delay: Long = 16) { scheduled = true; schedule(tick, delay) }

    private fun deliver() {
        if (x == 0f && y == 0f) return
        val complete = now() - lastInput >= 96 || abs(x) + abs(y) < 0.01f
        val dx = if (complete) x else x * 0.4f
        val dy = if (complete) y else y * 0.4f
        x -= dx; y -= dy; lastSent = now()
        send(dx, dy)
        if (x != 0f || y != 0f) enqueue()
    }

    fun finish() { if (!scheduled && (x != 0f || y != 0f)) enqueue() }
    fun flush() {
        cancel(tick); scheduled = false
        val dx = x; val dy = y
        x = 0f; y = 0f
        if (dx != 0f || dy != 0f) { lastSent = now(); send(dx, dy) }
    }
    fun reset() { cancel(tick); scheduled = false; x = 0f; y = 0f; lastSent = null }
}
