package au.com.michaelcarey.artouchpad

import kotlin.math.hypot

/** Separates a continuous back-and-forth gesture at its turning points.
 * A small reversal dead zone keeps finger jitter from creating extra strokes.
 * Points after a turn belong to the next stroke, not to the line being fitted.
 */
class CalibrationStrokeTracker(
    private val calibration: OrientationCalibration,
    private val reversalTravel: Float,
) {
    private var points = mutableListOf<OrientationCalibration.Point>()
    private var axis: Pair<Float, Float>? = null
    private var peakIndex = 0
    private var peakTravel = 0f
    private val initialCount = calibration.count

    fun add(point: OrientationCalibration.Point): OrientationCalibration.Rejection? {
        if (calibration.complete) return null
        points += point
        val dx = point.x - points.first().x
        val dy = point.y - points.first().y
        val direction = axis ?: run {
            val distance = hypot(dx, dy)
            if (distance < reversalTravel * 2) return null
            (dx / distance to dy / distance).also { axis = it }
        }
        val travel = dx * direction.first + dy * direction.second
        if (travel >= peakTravel) {
            peakIndex = points.lastIndex
            peakTravel = travel
        } else if (peakTravel - travel >= reversalTravel) {
            val rejection = calibration.addStroke(points.subList(0, peakIndex + 1))
            points = points.subList(peakIndex, points.size).toMutableList()
            axis = null
            peakIndex = 0
            peakTravel = 0f
            return rejection
        }
        return null
    }

    fun finish(): OrientationCalibration.Rejection? {
        if (calibration.complete) return null
        val rejection = calibration.addStroke(points)
        // A tiny trailing movement after a valid turn isn't a failed new swipe.
        return rejection.takeUnless {
            it == OrientationCalibration.Rejection.TOO_SHORT && calibration.count > initialCount
        }
    }
}
