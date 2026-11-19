package com.pgratz.artouchpad

import org.junit.Assert.*
import org.junit.Test

class DisplayBindingTest {
    private val glasses = MouseDisplayTarget(11, 1920, 1080)
    private val binding = DisplayBinding()

    @Test fun openingBeforeGlassesWaitsForTargetAndAcknowledgement() {
        binding.invalidate() // service connected, no glasses yet
        assertFalse(binding.markReady(binding.revision))
        assertTrue(binding.select(glasses))
        assertFalse(binding.isReady)
        assertTrue(binding.markReady(binding.revision))
        assertTrue(binding.isReady)
    }

    @Test fun glassesBeforeServiceAlsoWaitForAcknowledgement() {
        binding.select(glasses)
        val beforeConnection = binding.revision
        binding.invalidate() // service becomes available
        assertFalse(binding.markReady(beforeConnection))
        assertTrue(binding.markReady(binding.revision))
    }

    @Test fun repeatedDisplayNotificationsKeepLiveMouseReady() {
        binding.select(glasses)
        binding.markReady(binding.revision)
        val revision = binding.revision
        assertFalse(binding.select(glasses.copy()))
        assertTrue(binding.isReady)
        assertEquals(revision, binding.revision)
    }

    @Test fun disconnectRejectsPendingConnectionResult() {
        binding.select(glasses)
        val pending = binding.revision
        assertTrue(binding.select(null))
        assertFalse(binding.markReady(pending))
        assertFalse(binding.isReady)
        assertNull(binding.target)
    }

    @Test fun reconnectWithReusedIdRequiresFreshSetup() {
        binding.select(glasses)
        val previous = binding.revision
        binding.markReady(previous)
        binding.select(null)
        binding.select(glasses)
        assertFalse(binding.isReady)
        assertFalse(binding.markReady(previous))
        assertTrue(binding.markReady(binding.revision))
    }

    @Test fun newIdOrResolutionInvalidatesOldSetup() {
        binding.select(glasses)
        binding.markReady(binding.revision)
        val previous = binding.revision
        assertTrue(binding.select(glasses.copy(id = 12)))
        assertFalse(binding.isReady)
        assertFalse(binding.markReady(previous))
        binding.markReady(binding.revision)
        assertTrue(binding.select(glasses.copy(id = 12, width = 2560)))
        assertFalse(binding.isReady)
    }

    @Test fun serviceReconnectInvalidatesOldResultButRetainsTarget() {
        binding.select(glasses)
        val previous = binding.revision
        binding.markReady(previous)
        binding.invalidate()
        assertEquals(glasses, binding.target)
        assertFalse(binding.isReady)
        assertFalse(binding.markReady(previous))
        assertTrue(binding.markReady(binding.revision))
    }
}
