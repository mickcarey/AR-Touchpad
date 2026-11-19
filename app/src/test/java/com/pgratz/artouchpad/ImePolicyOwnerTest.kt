package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class ImePolicyOwnerTest {
    @Test fun restoresOriginalPolicyAfterMultipleChangesAndDisplays() {
        val values = mutableMapOf(5 to 2, 9 to 0)
        val owner = ImePolicyOwner({ values.getValue(it) }, { id, policy -> values[id] = policy })
        assertTrue(owner.apply(5, 1)); assertTrue(owner.apply(5, 0)); assertTrue(owner.apply(9, 1))
        owner.restoreAll(); assertEquals(mapOf(5 to 2, 9 to 0), values)
        owner.restoreAll(); assertEquals(mapOf(5 to 2, 9 to 0), values)
    }
    @Test fun userChangesWinAndBecomeTheNewBaseline() {
        val values = mutableMapOf(5 to 0)
        val owner = ImePolicyOwner({ values.getValue(it) }, { id, policy -> values[id] = policy })
        owner.apply(5, 1); values[5] = 2; owner.restoreAll(); assertEquals(2, values[5])
        owner.apply(5, 1); values[5] = 0; owner.apply(5, 1); owner.restoreAll(); assertEquals(0, values[5])
    }
    @Test fun unsupportedPolicyAndRemovedDisplayDoNotCrashCleanup() {
        val owner = ImePolicyOwner({ error("removed") }, { _, _ -> error("unsupported") })
        assertFalse(owner.apply(5, 1)); assertFalse(owner.apply(-1, 0)); assertFalse(owner.apply(5, 3))
        owner.restoreAll()
    }
    @Test fun failedWriteDoesNotClaimAnotherPolicy() {
        var current = 2
        val owner = ImePolicyOwner({ current }, { _, _ -> error("denied") })
        assertFalse(owner.apply(5, 1)); owner.restoreAll(); assertEquals(2, current)
    }
}
