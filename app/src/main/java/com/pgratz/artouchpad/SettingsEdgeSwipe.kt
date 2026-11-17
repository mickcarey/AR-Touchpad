package com.pgratz.artouchpad

import kotlin.math.abs

/** A horizontal sweep along either portrait top/bottom edge, in either direction.
 * Uses touch-surface coordinates, independent of cursor axis rotation. */
class SettingsEdgeSwipe(
    private val edgeWidth: Float,
    private val minimumTravel: Float,
    private val crossSlop: Float,
    private val maximumDurationMs: Long = 500,
    private val requiredWidthFraction: Float = 0.65f,
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
    private var requiredTravel = 0f
    private var direction = 1f

    fun begin(x: Float, y: Float, width: Float, height: Float, time: Long): Decision {
        startX = x; startY = y; startTime = time
        requiredTravel = width * requiredWidthFraction
        eligible = width > 0 && height > 0 && x in 0f..width && y in 0f..height &&
            (y <= edgeWidth || y >= height - edgeWidth)
        claimed = false
        active = true
        decision = if (eligible) Decision.PENDING else Decision.TRACKPAD
        return decision
    }

    fun update(x: Float, y: Float, time: Long, pointerCount: Int, released: Boolean): Decision {
        if (!active) return Decision.TRACKPAD
        val dx = x - startX
        val travel = abs(dx)
        val straight = abs(y - startY) <= crossSlop
        // Claim early to keep the cursor still; allow a relaxed full-width sweep after that.
        val deadline = if (claimed) maximumDurationMs * 6 else maximumDurationMs
        if (pointerCount > 1 || !straight || time - startTime > deadline ||
            (claimed && dx * direction < -crossSlop)) eligible = false
        if (!claimed && eligible && travel >= minOf(minimumTravel, requiredTravel)) {
            claimed = true
            direction = if (dx >= 0) 1f else -1f
        }
        decision = when {
            claimed && released && eligible && dx * direction >= requiredTravel -> Decision.OPEN_SETTINGS
            claimed -> Decision.CONSUMED
            eligible && !released -> Decision.PENDING
            else -> Decision.TRACKPAD
        }
        if (released) active = false
        return decision
    }
}
