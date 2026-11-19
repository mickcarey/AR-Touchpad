package com.pgratz.artouchpad

/** Serialize system-wide keyboard policy changes and restore only values we still own. */
internal class ImePolicyOwner(private val read: (Int) -> Int, private val write: (Int, Int) -> Unit) {
    private data class Owned(val original: Int, val applied: Int)
    private val policies = mutableMapOf<Int, Owned>()

    @Synchronized fun apply(display: Int, policy: Int): Boolean {
        if (display < 0 || policy !in 0..2) return false
        return runCatching {
            val current = read(display)
            val previous = policies[display]
            // A new system/user choice becomes the baseline, never overwritten on cleanup.
            val original = if (previous != null && current == previous.applied) previous.original else current
            write(display, policy)
            policies[display] = Owned(original, policy)
            true
        }.getOrDefault(false)
    }

    @Synchronized fun restoreAll() {
        val iterator = policies.iterator()
        while (iterator.hasNext()) {
            val (display, owned) = iterator.next()
            runCatching { if (read(display) == owned.applied) write(display, owned.original) }
            iterator.remove()
        }
    }
}
