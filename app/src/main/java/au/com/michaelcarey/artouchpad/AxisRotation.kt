package au.com.michaelcarey.artouchpad

private const val DIAGONAL = 0.70710677f

// Screen coordinates have positive Y pointing down, so positive angles turn clockwise.
// Exact quarter-turn coefficients avoid leaking motion onto the perpendicular axis.
enum class AxisRotation(val degrees: Int, private val cos: Float, private val sin: Float) {
    NONE(0, 1f, 0f),
    CLOCKWISE_45(45, DIAGONAL, DIAGONAL),
    CLOCKWISE_90(90, 0f, 1f),
    COUNTERCLOCKWISE_45(-45, DIAGONAL, -DIAGONAL),
    COUNTERCLOCKWISE_90(-90, 0f, -1f);

    fun rotate(dx: Float, dy: Float): Pair<Float, Float> =
        (dx * cos - dy * sin) to (dx * sin + dy * cos)

    companion object {
        fun fromDegrees(degrees: Int): AxisRotation =
            entries.firstOrNull { it.degrees == degrees } ?: NONE
    }
}
