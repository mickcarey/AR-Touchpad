package com.pgratz.artouchpad

import kotlin.math.abs

/** Tracks a whole contact sequence, including staggered finger arrivals and releases. */
internal class MultiFingerTapTracker(
    private val slop: Float = 12f,
    private val timeoutMs: Long = 220L,
) {
    data class Contact(val id: Long, val x: Float, val y: Float, val pressed: Boolean)

    var maxFingers = 0
        private set
    var releasing = false
        private set
    private var activeCount = 0
    private var startedAt = 0L
    private var eligible = false
    private val origins = mutableMapOf<Long, Contact>()

    fun cancelTap() { eligible = false }

    /** Returns 2 or 3 once, after the last finger lifts; otherwise returns null. */
    fun update(timeMs: Long, contacts: List<Contact>): Int? {
        if (activeCount == 0) {
            origins.clear()
            maxFingers = 0
            releasing = false
            startedAt = timeMs
            eligible = true
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
        activeCount = contacts.count { it.pressed }
        maxFingers = maxOf(maxFingers, activeCount)
        if (contacts.any { !it.pressed }) releasing = true
        if (activeCount != 0) return null
        return maxFingers.takeIf {
            eligible && it in 2..3 && timeMs - startedAt < timeoutMs
        }
    }
}
