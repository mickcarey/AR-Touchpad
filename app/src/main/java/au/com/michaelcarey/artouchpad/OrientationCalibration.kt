package au.com.michaelcarey.artouchpad

import kotlin.math.*

/** Cached coefficients: calibration must not add trigonometry to every mouse event. */
class CalibratedRotation(val degrees: Float) {
    private val radians = Math.toRadians(degrees.toDouble())
    private val cosine = cos(radians).toFloat()
    private val sine = sin(radians).toFloat()
    fun rotate(x: Float, y: Float) = (x * cosine - y * sine) to (x * sine + y * cosine)
}

/** Fits each stroke's principal axis, then averages equally across alternating strokes.
 * The first stroke is explicitly UP; an undirected line alone cannot resolve 180 degrees.
 */
class OrientationCalibration(private val minimumTravel: Float) {
    data class Point(val x: Float, val y: Float)
    enum class Rejection { TOO_SHORT, NOT_STRAIGHT, INCONSISTENT }
    private val directions = mutableListOf<Pair<Double, Double>>()
    val count get() = directions.size
    val complete get() = count == STROKES
    val nextIsUp get() = count % 2 == 0
    val degrees: Float?
        get() {
            if (!complete) return null
            val angle = Math.toDegrees(-PI / 2 - atan2(directions.sumOf { it.second }, directions.sumOf { it.first }))
            return (((angle + 540) % 360) - 180).toFloat()
        }

    fun addStroke(points: List<Point>): Rejection? {
        if (complete) return null
        if (points.size < 3 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return Rejection.TOO_SHORT
        val dx = (points.last().x - points.first().x).toDouble()
        val dy = (points.last().y - points.first().y).toDouble()
        val distance = hypot(dx, dy)
        if (distance < minimumTravel) return Rejection.TOO_SHORT
        // Ignore sub-millimetre jitter when measuring path length. Pausing at a turn
        // can otherwise make a straight stroke look like a long, winding path.
        var anchor = points.first()
        var path = 0.0
        points.drop(1).forEach { point ->
            val step = hypot((point.x - anchor.x).toDouble(), (point.y - anchor.y).toDouble())
            if (step >= minimumTravel * 0.05) { path += step; anchor = point }
        }
        path += hypot((points.last().x - anchor.x).toDouble(), (points.last().y - anchor.y).toDouble())
        if (distance / path < 0.88) return Rejection.NOT_STRAIGHT
        val mx = points.map { it.x.toDouble() }.average()
        val my = points.map { it.y.toDouble() }.average()
        val xx = points.sumOf { (it.x - mx).pow(2) }
        val yy = points.sumOf { (it.y - my).pow(2) }
        val xy = points.sumOf { (it.x - mx) * (it.y - my) }
        val axis = 0.5 * atan2(2 * xy, xx - yy)
        var x = cos(axis)
        var y = sin(axis)
        val perpendicularRms = sqrt(points.sumOf { ((it.x - mx) * -y + (it.y - my) * x).pow(2) } / points.size)
        if (perpendicularRms / distance > 0.06) return Rejection.NOT_STRAIGHT
        // Give the fitted line the stroke's direction, then turn DOWN strokes into UP.
        if (x * dx + y * dy < 0) { x = -x; y = -y }
        if (!nextIsUp) { x = -x; y = -y }
        if (directions.isNotEmpty()) {
            val sx = directions.sumOf { it.first }
            val sy = directions.sumOf { it.second }
            if ((x * sx + y * sy) / hypot(sx, sy) < cos(Math.toRadians(12.0))) return Rejection.INCONSISTENT
        }
        directions += x to y
        return null
    }

    companion object { const val STROKES = 6 }
}
