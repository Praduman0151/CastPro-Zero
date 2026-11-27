package com.castpro.zero.phone.capture

import com.castpro.zero.protocol.SenderStats
import org.junit.Assert.*
import org.junit.Test

class SenderTelemetryStateTests {
    private val measured = SenderStats(
        framesSent = 120, bytesSent = 240_000, pliCount = 1, rttMs = 12,
        fps = 58, mbps = 4.5f, codec = "c2.qti.avc.encoder",
        resolution = "1920×1080", audioFramesSent = 70, audioActive = true
    )

    @Test fun pliUpdateKeepsMeasuredSamples() {
        val state = SenderTelemetryState()
        assertNull(state.onPli(2))
        state.publishMeasured(measured)
        assertEquals(measured.copy(pliCount = 2), state.onPli(2))
        assertEquals(measured.copy(pliCount = 2), state.snapshot())
    }

    @Test fun rttUpdateKeepsMeasuredSamples() {
        val state = SenderTelemetryState()
        assertNull(state.onRtt(31))
        state.publishMeasured(measured)
        assertEquals(measured.copy(rttMs = 31), state.onRtt(31))
    }

    @Test fun measuredSnapshotReplacesOldTelemetryAndResetClearsIt() {
        val state = SenderTelemetryState()
        state.publishMeasured(measured)
        assertEquals(measured, state.snapshot())
        state.reset()
        assertNull(state.onPli(1))
        assertNull(state.snapshot())
    }
}
