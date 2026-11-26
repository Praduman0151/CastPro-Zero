package com.castpro.zero.tv

import com.castpro.zero.protocol.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class DecoderRecoveryTests {
    private val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0, 0, 0, 1, 0x68, 1)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)

    @Test fun surfaceRecreationReplaysCachedCsdAndRequestsPli() {
        val state = DecoderSessionState(VideoCodec.AVC)
        assertTrue(state.onCsd(csd))
        assertArrayEquals(csd, state.csdForDecoder())
        assertTrue(state.allowFrame(idr))
        assertArrayEquals(csd, state.onSurfaceRecreated())
        assertEquals(1L, state.csdReplays)
        assertEquals(1L, state.pliCount)
        assertFalse(state.allowFrame(byteArrayOf(0, 0, 0, 1, 0x41, 1)))
        assertTrue(state.allowFrame(idr))
    }

    @Test fun pliReplaysCachedCsdAndInvalidCsdIsRejected() {
        val state = DecoderSessionState(VideoCodec.AVC)
        assertFalse(state.onCsd(byteArrayOf(0, 0, 0, 1, 0x65, 1)))
        assertNull(state.csdForDecoder())
        assertTrue(state.onCsd(csd))
        assertArrayEquals(csd, state.onPli())
        assertEquals(1L, state.pliCount)
        state.resetSession()
        assertNull(state.csdForDecoder())
    }
}
