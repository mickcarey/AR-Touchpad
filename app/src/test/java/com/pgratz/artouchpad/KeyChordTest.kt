package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class KeyChordTest {
    private val keys = listOf(1 to 1, 2 to 2, 3 to 0)
    @Test fun releasesInReverseOrderAndClearsModifierBits() {
        val events = mutableListOf<Triple<Int, Boolean, Int>>()
        sendKeyChord(keys) { key, down, meta -> events += Triple(key, down, meta) }
        assertEquals(listOf(Triple(1,true,1), Triple(2,true,3), Triple(3,true,3),
            Triple(3,false,3), Triple(2,false,1), Triple(1,false,0)), events)
    }
    @Test fun failureAtEveryDownStillAttemptsEveryNecessaryRelease() {
        for (failAt in 1..3) {
            val released = mutableListOf<Int>()
            val result = runCatching {
                sendKeyChord(keys) { key, down, _ ->
                    if (down && key == failAt) error("injection failed")
                    if (!down) released += key
                }
            }
            assertTrue(result.isFailure)
            assertEquals((1..failAt).reversed().toList(), released)
        }
    }
    @Test fun oneReleaseFailureCannotLeaveRemainingModifiersHeld() {
        val released = mutableListOf<Int>()
        sendKeyChord(keys) { key, down, _ ->
            if (!down) { released += key; if (key == 3) error("release failed") }
        }
        assertEquals(listOf(3,2,1), released)
    }
}
