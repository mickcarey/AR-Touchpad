package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class CalibrationStrokeTrackerTest {
    private fun stroke(up: Boolean, angle: Float = -36f, length: Float = 300f) =
        (0..40).map { i ->
            val y = if (up) length * (1 - i / 40f) else length * i / 40f
            val (x, rotatedY) = CalibratedRotation(-angle).rotate(0f, y)
            OrientationCalibration.Point(x, rotatedY)
        }

    @Test fun sixContinuousStrokesEndingAtTheirStartingPointCalibrateCorrectly() {
        val calibration = OrientationCalibration(80f)
        val tracker = CalibrationStrokeTracker(calibration, 16f)
        repeat(6) { i -> stroke(i % 2 == 0).forEach { assertNull(tracker.add(it)) } }
        assertEquals(5, calibration.count)
        assertNull(tracker.finish())
        assertEquals(6, calibration.count)
        assertEquals(-36f, calibration.degrees!!, 0.001f)
    }

    @Test fun progressAdvancesAtEachTurnBeforeFingerLifts() {
        val calibration = OrientationCalibration(80f)
        val tracker = CalibrationStrokeTracker(calibration, 16f)
        stroke(true).forEach { tracker.add(it) }
        assertEquals(0, calibration.count)
        stroke(false).take(6).forEach { tracker.add(it) }
        assertEquals(1, calibration.count)
        assertFalse(calibration.nextIsUp)
        assertNull(tracker.finish()) // short trailing movement must not erase success
        assertEquals(1, calibration.count)
    }

    @Test fun liftingBetweenStrokesStillWorks() {
        val calibration = OrientationCalibration(80f)
        repeat(6) { i ->
            val tracker = CalibrationStrokeTracker(calibration, 16f)
            stroke(i % 2 == 0, angle = 52.4f).forEach { assertNull(tracker.add(it)) }
            assertNull(tracker.finish())
            assertEquals(i + 1, calibration.count)
        }
        assertEquals(52.4f, calibration.degrees!!, 0.001f)
    }

    @Test fun smallJitterAtATurnDoesNotCountExtraStrokes() {
        val calibration = OrientationCalibration(80f)
        val tracker = CalibrationStrokeTracker(calibration, 16f)
        stroke(true, angle = 0f).forEach { tracker.add(it) }
        repeat(20) { tracker.add(OrientationCalibration.Point(0f, if (it % 2 == 0) 3f else 0f)) }
        assertEquals(0, calibration.count)
        stroke(false, angle = 0f).forEach { tracker.add(it) }
        assertEquals(1, calibration.count)
        assertNull(tracker.finish())
        assertEquals(2, calibration.count)
    }

    @Test fun aTapOrGenuinelyShortMovementIsStillRejected() {
        val calibration = OrientationCalibration(80f)
        val tracker = CalibrationStrokeTracker(calibration, 16f)
        stroke(true, length = 30f).forEach { tracker.add(it) }
        assertEquals(OrientationCalibration.Rejection.TOO_SHORT, tracker.finish())
        assertEquals(0, calibration.count)
    }
}
