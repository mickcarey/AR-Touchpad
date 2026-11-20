package au.com.michaelcarey.artouchpad

import kotlin.math.exp

/** Integrates a first-order position filter over each sample's elapsed time.
 * The lag is retained as distance, not velocity; finish preserves the exact endpoint.
 */
internal class MotionFilter {
    private var lastTime: Long? = null
    private var lagX = 0f
    private var lagY = 0f

    fun move(dx: Float, dy: Float, time: Long, smoothing: Smoothing): Pair<Float, Float> {
        if (!dx.isFinite() || !dy.isFinite()) return 0f to 0f
        val dt = lastTime?.let { (time - it).coerceIn(1, 100).toFloat() } ?: 16f
        lastTime = time
        val tau = smoothing.timeConstantMs
        if (tau == 0f) {
            val tail = finish()
            lastTime = time
            return dx + tail.first to dy + tail.second
        }
        val decay = exp(-dt / tau)
        val nextX = dx / dt * tau * (1 - decay) + lagX * decay
        val nextY = dy / dt * tau * (1 - decay) + lagY * decay
        val result = dx + lagX - nextX to dy + lagY - nextY
        lagX = nextX; lagY = nextY
        return result
    }

    fun finish(): Pair<Float, Float> = (lagX to lagY).also { reset() }
    fun reset(time: Long? = null) { lastTime = time; lagX = 0f; lagY = 0f }
}
