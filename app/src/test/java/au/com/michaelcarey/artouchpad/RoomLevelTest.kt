package au.com.michaelcarey.artouchpad

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomLevelTest {
    @Test fun fullScaleAndHalfAmplitudeUseTheDigitalReference() {
        assertEquals(0, dbfs(1.0))
        assertEquals(-6, dbfs(0.5))
        assertEquals(-20, dbfs(0.1))
        assertEquals(-90, dbfs(0.0))
    }

    @Test fun quietReferenceReportsADifferenceOnlyWhenThereIsAMeasurement() {
        assertEquals("-40 dBFS · Peak -25 · +6 dB vs quiet", RoomLevel(-40, -25).label(-46))
        assertEquals("No mic signal", RoomLevel(status = "No mic signal").label(-46))
    }
}
