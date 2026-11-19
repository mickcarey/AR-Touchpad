package com.pgratz.artouchpad

/** Android's native vector styles recolour every system cursor without replacing its shape. */
internal data class CursorPulsePlan(val colour: Int, val scale: Float) {
    companion object {
        fun create(originalColour: String, originalScale: String, choice: Int): CursorPulsePlan {
            val colours = (1..5).filter { it != originalColour.toIntOrNull() }
            val scale = originalScale.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..2.5f } ?: 1f
            // Grow from the user's default, up to Android's native size limit.
            val pulseScale = (scale * 1.25f).coerceAtMost(2.5f)
            return CursorPulsePlan(colours[Math.floorMod(choice, colours.size)], pulseScale)
        }
    }
}
