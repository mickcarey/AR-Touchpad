package au.com.michaelcarey.artouchpad

import org.junit.Assert.*
import org.junit.Test

class CursorPulsePlanTest {
    @Test fun pulseAlwaysUsesADifferentVisibleColour() {
        for (original in 0..5) for (choice in -20..20) {
            val plan = CursorPulsePlan.create(original.toString(), "2.5", choice)
            assertTrue(plan.colour in 1..5)
            assertNotEquals(original, plan.colour)
        }
    }

    @Test fun enlargementRespectsExistingUserScale() {
        assertEquals(2.5f, CursorPulsePlan.create("2", "2.5", 1).scale, 0.0001f)
        assertEquals(1.25f, CursorPulsePlan.create("null", "null", 1).scale, 0.0001f)
        assertEquals(1.875f, CursorPulsePlan.create("2", "1.5", 1).scale, 0.0001f)
        assertEquals(2.5f, CursorPulsePlan.create("2", "2.0", 1).scale, 0.0001f)
    }

    @Test fun invalidScaleCannotProduceAnInvalidCursor() {
        for (value in listOf("NaN", "Infinity", "-1", "0", "0.5", "3", "invalid"))
            assertEquals(1.25f, CursorPulsePlan.create("2", value, Int.MIN_VALUE).scale, 0f)
    }

    @Test fun sizePulseNeverShrinksTheUsersCursor() {
        for (step in 0..150) {
            val original = 1f + step / 100f
            val plan = CursorPulsePlan.create("2", original.toString(), 1)
            assertTrue(plan.scale >= original)
            assertTrue(plan.scale <= 2.5f)
        }
    }
}
