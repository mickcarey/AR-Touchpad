package com.pgratz.artouchpad

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class OrientationCalibrationTest {
    private fun stroke(rotation: Float, up: Boolean, length: Float = 300f, noise: Float = 0f): List<OrientationCalibration.Point> {
        val inverse = CalibratedRotation(-rotation)
        return (0..40).map { index ->
            val (x, y) = inverse.rotate(sin(index * 1.7f) * noise,
                length * index / 40 * if (up) -1 else 1)
            OrientationCalibration.Point(500f + x, 800f + y)
        }
    }

    @Test fun arbitraryAnglesAndInvertedPhoneResolveWithoutRoundingToPreset() {
        for (angle in listOf(-36.2f, 17.7f, 0f, 90f, -90f, 179.4f, -179.4f)) {
            val calibration = OrientationCalibration(80f)
            repeat(6) { assertNull(calibration.addStroke(stroke(angle, it % 2 == 0))) }
            assertEquals(angle, calibration.degrees!!, 0.001f)
            val physicalUp = CalibratedRotation(-angle).rotate(0f, -300f)
            val corrected = CalibratedRotation(calibration.degrees!!).rotate(physicalUp.first, physicalUp.second)
            assertEquals(0f, corrected.first, 0.001f)
            assertEquals(-300f, corrected.second, 0.001f)
        }
    }

    @Test fun averagesRepeatedStrokesWithSmallAngleVariationAndFingerNoise() {
        val calibration = OrientationCalibration(80f)
        listOf(-1f, 1f, -0.5f, 0.5f, -2f, 2f).forEachIndexed { i, variation ->
            assertNull(calibration.addStroke(stroke(-36f + variation, i % 2 == 0, noise = 1f)))
        }
        assertEquals(-36f, calibration.degrees!!, 0.1f)
    }

    @Test fun shortAndCurvedStrokesDoNotAdvanceDirectionOrProduceResult() {
        val calibration = OrientationCalibration(80f)
        assertEquals(OrientationCalibration.Rejection.TOO_SHORT, calibration.addStroke(stroke(0f, true, length = 40f)))
        val curve = (0..40).map { OrientationCalibration.Point(sin(it / 40f * PI).toFloat() * 200f, -it * 10f) }
        assertEquals(OrientationCalibration.Rejection.NOT_STRAIGHT, calibration.addStroke(curve))
        assertTrue(calibration.nextIsUp)
        assertEquals(0, calibration.count)
        assertNull(calibration.degrees)
    }

    @Test fun wrongDirectionAndChangedGripAreRejectedAndCanBeRetried() {
        val calibration = OrientationCalibration(80f)
        assertNull(calibration.addStroke(stroke(-36f, true)))
        assertEquals(OrientationCalibration.Rejection.INCONSISTENT, calibration.addStroke(stroke(-36f, true)))
        assertEquals(OrientationCalibration.Rejection.INCONSISTENT, calibration.addStroke(stroke(-60f, false)))
        assertFalse(calibration.nextIsUp)
        assertEquals(1, calibration.count)
        assertNull(calibration.addStroke(stroke(-36f, false)))
        assertEquals(2, calibration.count)
    }

    @Test fun onlySixValidStrokesProduceResultAndFurtherStrokesDoNotChangeIt() {
        val calibration = OrientationCalibration(80f)
        repeat(5) {
            assertNull(calibration.addStroke(stroke(37f, it % 2 == 0)))
            assertNull(calibration.degrees)
        }
        calibration.addStroke(stroke(37f, false))
        assertTrue(calibration.complete)
        calibration.addStroke(stroke(90f, true))
        assertEquals(6, calibration.count)
        assertEquals(37f, calibration.degrees!!, 0.001f)
    }

    @Test fun calibrationOverridesStoredPresetForBothAxesAndClearingItRestoresPreset() {
        val state = TouchpadState(axisRotation = AxisRotation.CLOCKWISE_90, calibratedRotationDegrees = -36.2f)
        val expected = CalibratedRotation(-36.2f).rotate(13f, -7f)
        assertEquals(expected, state.rotateInput(13f, -7f))
        assertEquals(7f to 13f, state.copy(calibratedRotationDegrees = null).rotateInput(13f, -7f))
    }

    @Test fun calibrationConsumesInputEvenWhenSettingsTemporarilyUnlockPocketMode() {
        val state = TouchpadState(mouseReady = true, pocketMode = true, showSettings = true)
        assertTrue(state.touchpadInputEnabled)
        val calibrating = state.copy(calibratingOrientation = true)
        assertFalse(calibrating.touchpadInputEnabled)
        assertTrue(calibrating.pocketMode)
        assertTrue(calibrating.copy(calibratingOrientation = false).touchpadInputEnabled)
        assertFalse(calibrating.copy(calibratingOrientation = false, showSettings = false).touchpadInputEnabled)
    }
}
