package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class DecodeGateRecoveryTests {
    private val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0, 0, 0, 1, 0x68, 1)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 1)

    @Test fun decoderRecreationRejectsAllFramesUntilCsdIsReapplied() {
        val gate = DecodeGate(VideoCodec.AVC)
        assertTrue(gate.onCsd(csd))
        assertTrue(gate.allowFrame(idr))
        assertEquals(DecodeGate.State.OPEN, gate.state)

        gate.onDecoderRecreated()
        assertEquals(DecodeGate.State.AWAITING_CSD, gate.state)
        // An IDR alone must not reach a freshly created decoder that has no CSD yet.
        assertFalse(gate.allowFrame(idr))
        assertEquals(DecodeGate.State.AWAITING_CSD, gate.state)

        assertTrue(gate.onCsd(csd))
        assertEquals(DecodeGate.State.AWAITING_IDR, gate.state)
        assertFalse(gate.allowFrame(pFrame))
        assertTrue(gate.allowFrame(idr))
        assertEquals(DecodeGate.State.OPEN, gate.state)
    }

    @Test fun surfaceReattachOnFreshGateWaitsForCsdThenIdr() {
        val gate = DecodeGate(VideoCodec.AVC)
        gate.onDecoderRecreated()
        assertFalse(gate.allowFrame(idr))
        assertTrue(gate.onCsd(csd))
        assertFalse(gate.allowFrame(pFrame))
        assertTrue(gate.allowFrame(idr))
    }
}
