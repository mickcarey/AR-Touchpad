package com.pgratz.artouchpad

import kotlin.math.abs

/** Phone stays portrait-locked. At 90° anticlockwise, its bottom edge is the user's
 * right edge, and a downward stroke travels right-to-left in phone coordinates. */
class SettingsEdgeSwipe(
    private val edgeWidth: Float,
    private val minimumTravel: Float,
    private val crossSlop: Float,
    private val maximumDurationMs: Long = 500,
) {
    enum class Decision { TRACKPAD, PENDING, CONSUMED, OPEN_SETTINGS }
    var decision = Decision.TRACKPAD
        private set
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var eligible = false
    private var claimed = false
    private var active = false

    fun begin(x: Float, y: Float, width: Float, height: Float, time: Long): Decision {
        startX = x; startY = y; startTime = time
        // Exclude corners; Android owns system navigation gestures right at the boundary.
        eligible = y >= height - edgeWidth && x >= crossSlop && x <= width - crossSlop
        claimed = false
        active = true
        decision = if (eligible) Decision.PENDING else Decision.TRACKPAD
        return decision
    }

    fun update(x: Float, y: Float, time: Long, pointerCount: Int, released: Boolean): Decision {
        if (!active) return Decision.TRACKPAD
        val travel = startX - x
        val straight = abs(y - startY) <= crossSlop
        val deadline = if (claimed) maximumDurationMs * 3 else maximumDurationMs
        if (pointerCount > 1 || !straight || travel < -crossSlop || time - startTime > deadline) eligible = false
        if (eligible && travel >= minimumTravel) claimed = true
        decision = when {
            claimed && released && eligible && travel >= minimumTravel -> Decision.OPEN_SETTINGS
            claimed -> Decision.CONSUMED
            eligible && !released -> Decision.PENDING
            else -> Decision.TRACKPAD
        }
        if (released) active = false
        return decision
    }
}
