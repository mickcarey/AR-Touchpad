package com.pgratz.artouchpad

internal data class MouseDisplayTarget(val id: Int, val width: Int, val height: Int)

/** Readiness belongs to one connection attempt, never just a reusable display id. */
internal class DisplayBinding {
    var target: MouseDisplayTarget? = null
        private set
    var revision = 0L
        private set
    var isReady = false
        private set

    fun select(target: MouseDisplayTarget?): Boolean {
        if (this.target == target) return false
        this.target = target
        invalidate()
        return true
    }

    fun invalidate() {
        revision++
        isReady = false
    }

    fun markReady(revision: Long): Boolean {
        if (this.revision != revision || target == null) return false
        isReady = true
        return true
    }
}
