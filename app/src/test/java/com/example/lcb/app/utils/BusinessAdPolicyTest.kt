package com.example.lcb.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessAdPolicyTest {
    @Test
    fun `gate allows first request and enforces shared cooldown`() {
        var now = 1_000L
        val gate = InterstitialFrequencyGate(
            minimumIntervalMs = 90_000L,
            clock = AdMonotonicClock { now },
        )

        assertTrue(gate.tryAcquire())
        now += 89_999L
        assertFalse(gate.tryAcquire())
        now += 1L
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `clock reset does not permanently block a new process session`() {
        var now = 10_000L
        val gate = InterstitialFrequencyGate(90_000L, AdMonotonicClock { now })

        assertTrue(gate.tryAcquire())
        now = 100L
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `initial delay protects the session from back to back launch ads`() {
        var now = 5_000L
        val gate = InterstitialFrequencyGate(
            minimumIntervalMs = 90_000L,
            clock = AdMonotonicClock { now },
            initialDelayMs = 30_000L,
        )

        assertFalse(gate.tryAcquire())
        now += 30_000L
        assertTrue(gate.tryAcquire())
    }
}
