package au.com.michaelcarey.artouchpad

/** Coalesces motion on one thread, with a scheduled deadline even if input stops. */
internal class MotionBatcher(
    private val now: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (Float, Float) -> Unit,
    private val intervalMs: Long = 16,
) {
    private var x = 0f
    private var y = 0f
    private var lastSent: Long? = null
    private var scheduled = false
    private val deadline = Runnable { scheduled = false; flush() }

    fun add(dx: Float, dy: Float) {
        x += dx
        y += dy
        val wait = lastSent?.let { (intervalMs - (now() - it)).coerceAtLeast(0) } ?: 0
        if (wait == 0L) flush()
        else if (!scheduled) {
            scheduled = true
            schedule(deadline, wait)
        }
    }

    fun flush() {
        if (scheduled) cancel(deadline)
        scheduled = false
        val dx = x
        val dy = y
        x = 0f
        y = 0f
        if (dx == 0f && dy == 0f) return
        lastSent = now()
        send(dx, dy)
    }

    fun reset() {
        cancel(deadline)
        scheduled = false
        x = 0f
        y = 0f
        lastSent = null
    }
}
