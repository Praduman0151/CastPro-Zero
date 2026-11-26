package com.castpro.zero.tv

import org.junit.Assert.*
import org.junit.Test

class SurfaceRecoveryStateTests {
    @Test fun recreationRetainsCsdAndRequestsPliAgain() {
        val state = SurfaceRecoveryState()
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        state.onCsd(csd); state.onSurfaceAvailable()
        assertTrue(state.decoderReady); assertEquals(1, state.pliRequests)
        state.onSurfaceDestroyed(); assertFalse(state.decoderReady)
        state.onSurfaceAvailable()
        assertArrayEquals(csd, state.cachedCsd)
        assertTrue(state.decoderReady); assertEquals(2, state.pliRequests)
    }
    @Test fun stopClearsCachedCsd() {
        val state = SurfaceRecoveryState()
        state.onCsd(byteArrayOf(1)); state.onSurfaceAvailable(); state.onStopOrDisconnect()
        assertNull(state.cachedCsd); assertFalse(state.surfaceAvailable); assertFalse(state.decoderReady)
    }
}
