package au.com.michaelcarey.artouchpad

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

class AxisRotationTest {
    @Test
    fun defaultPreservesBothAxes() {
        assertEquals(12f to -7f, AxisRotation.NONE.rotate(12f, -7f))
    }

    @Test
    fun quarterTurnsMapBothAxesWithoutPerpendicularDrift() {
        assertEquals(0f to 10f, AxisRotation.CLOCKWISE_90.rotate(10f, 0f))
        assertEquals(-10f to 0f, AxisRotation.CLOCKWISE_90.rotate(0f, 10f))
        assertEquals(0f to -10f, AxisRotation.COUNTERCLOCKWISE_90.rotate(10f, 0f))
        assertEquals(10f to 0f, AxisRotation.COUNTERCLOCKWISE_90.rotate(0f, 10f))
    }

    @Test
    fun diagonalRotationMapsRightAndDownInEachDirection() {
        val component = sqrt(50f)
        assertDelta(component, component, AxisRotation.CLOCKWISE_45.rotate(10f, 0f))
        assertDelta(-component, component, AxisRotation.CLOCKWISE_45.rotate(0f, 10f))
        assertDelta(component, -component, AxisRotation.COUNTERCLOCKWISE_45.rotate(10f, 0f))
        assertDelta(component, component, AxisRotation.COUNTERCLOCKWISE_45.rotate(0f, 10f))
    }

    @Test
    fun rotationPreservesTravelDistanceAndZeroMotion() {
        AxisRotation.entries.forEach { rotation ->
            listOf(3f to 4f, -3f to 4f, 3f to -4f, -3f to -4f).forEach { (x, y) ->
                val (dx, dy) = rotation.rotate(x, y)
                assertEquals(5f, sqrt(dx * dx + dy * dy), 0.00001f)
            }
            assertDelta(0f, 0f, rotation.rotate(0f, 0f))
        }
    }

    @Test
    fun oppositeRotationsCancel() {
        listOf(45, 90).forEach { degrees ->
            val (dx, dy) = AxisRotation.fromDegrees(degrees).rotate(12f, -7f)
            assertDelta(12f, -7f, AxisRotation.fromDegrees(-degrees).rotate(dx, dy))
        }
    }

    @Test
    fun savedDegreesRestoreAndUnknownValuesUseDefault() {
        AxisRotation.entries.forEach { rotation ->
            assertEquals(rotation, AxisRotation.fromDegrees(rotation.degrees))
        }
        assertEquals(AxisRotation.NONE, AxisRotation.fromDegrees(123))
    }

    private fun assertDelta(x: Float, y: Float, actual: Pair<Float, Float>) {
        assertEquals(x, actual.first, 0.00001f)
        assertEquals(y, actual.second, 0.00001f)
    }
}
