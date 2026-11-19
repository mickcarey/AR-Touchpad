package com.pgratz.artouchpad

import kotlin.math.abs

/** Owns the entire four-finger chord, including staggered arrivals and trailing releases. */
internal class FourFingerGesture(
    private val slop: Float,
    private val arrivalMs: Long = 200,
    private val tapMs: Long = 350,
    private val holdMs: Long = 800,
) {
    enum class Decision { PASS, CONSUMED, OPEN_SETTINGS, TOGGLE_POCKET }
    private val origins = mutableMapOf<Long, MultiFingerTapTracker.Contact>()
    private var active = false
    private var startedAt = 0L
    private var fourAt: Long? = null
    private var count = 0
    private var claimed = false
    private var eligible = false
    private var releasing = false
    private var fired = false

    val holdDeadline: Long?
        get() = fourAt?.takeIf { active && eligible && count == 4 && !releasing && !fired }
            ?.plus(holdMs)

    fun cancel() { eligible = false }

    fun update(time: Long, contacts: List<MultiFingerTapTracker.Contact>): Decision {
        if (!active) {
            if (contacts.none { it.pressed }) return Decision.PASS
            active = true
            origins.clear()
            startedAt = time
            fourAt = null
            claimed = false
            eligible = true
            releasing = false
            fired = false
        }
        for (contact in contacts) {
            val origin = origins[contact.id]
            if (origin == null) {
                if (releasing || !contact.pressed) eligible = false
                origins[contact.id] = contact
            } else if (abs(contact.x - origin.x) > slop || abs(contact.y - origin.y) > slop) {
                eligible = false
            }
        }
        count = contacts.count { it.pressed }
        if (count >= 4) claimed = true
        if (origins.size > 4) eligible = false
        if (fourAt == null && time - startedAt > arrivalMs) eligible = false
        if (count == 4 && fourAt == null && eligible) fourAt = time
        if (contacts.any { !it.pressed }) releasing = true
        if (count == 0) {
            active = false
            return if (claimed && eligible && !fired && time - startedAt <= tapMs)
                Decision.OPEN_SETTINGS
            else if (claimed) Decision.CONSUMED else Decision.PASS
        }
        if (onTime(time) == Decision.TOGGLE_POCKET) return Decision.TOGGLE_POCKET
        return if (claimed) Decision.CONSUMED else Decision.PASS
    }

    /** Called by a timer so a motionless hold doesn't need another touch event. */
    fun onTime(time: Long): Decision {
        val deadline = holdDeadline ?: return Decision.PASS
        if (time < deadline) return Decision.PASS
        fired = true
        return Decision.TOGGLE_POCKET
    }
}
