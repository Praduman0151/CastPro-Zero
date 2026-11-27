package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class StreamHealthCountersTests {
    @Test fun decoderBackpressureNeverRequestsPli() {
        val c = StreamHealthCounters()
        repeat(20) { c.onDecoderBackpressure() }
        val s = c.snapshot()
        assertEquals(20L, s.backpressureDrops)
        assertEquals(0L, s.pliCount)
        assertTrue(s.pliReasons.isEmpty())
    }

    @Test fun reassemblyLossRequestsExactlyOnePli() {
        val c = StreamHealthCounters()
        c.onReassemblyLoss()
        val s = c.snapshot()
        assertEquals(1L, s.reassemblyDrops)
        assertEquals(1L, s.pliCount)
        assertEquals(1, s.pliReasons[PLI_REASON_REASSEMBLY])
    }

    @Test fun reassemblyDropCanBeCountedWithoutRequestingPli() {
        val c = StreamHealthCounters()
        repeat(3) { c.onReassemblyDrop() }
        val s = c.snapshot()
        assertEquals(3L, s.reassemblyDrops)
        assertEquals(0L, s.pliCount)
    }

    @Test fun setupFailureCanBeCountedWithoutRequestingPli() {
        val c = StreamHealthCounters()
        repeat(2) { c.onSetupFailureOnly() }
        val s = c.snapshot()
        assertEquals(2L, s.setupFailures)
        assertEquals(0L, s.pliCount)
    }

    @Test fun decoderSetupFailureRequestsPliWithReason() {
        val c = StreamHealthCounters()
        c.onDecoderSetupFailure()
        val s = c.snapshot()
        assertEquals(1L, s.setupFailures)
        assertEquals(1L, s.pliCount)
        assertEquals(1, s.pliReasons[PLI_REASON_DECODER_SETUP])
    }

    @Test fun explicitPliIsCountedByReason() {
        val c = StreamHealthCounters()
        c.onPli(PLI_REASON_SURFACE)
        val s = c.snapshot()
        assertEquals(1L, s.pliCount)
        assertEquals(1, s.pliReasons[PLI_REASON_SURFACE])
    }

    @Test fun resetClearsAllCounters() {
        val c = StreamHealthCounters()
        c.onDecoderBackpressure(); c.onReassemblyLoss(); c.onPli(PLI_REASON_SURFACE)
        c.reset()
        assertEquals(HealthSnapshot(0, 0, 0, 0, emptyMap()), c.snapshot())
    }
}
