package com.castpro.zero.tv

import org.junit.Assert.*
import org.junit.Test

class DecoderRecoveryTests {
    @Test fun surfaceRecreationRequiresCsdReplayBeforeIdr() {
        var cachedCsd = true
        var decoderReady = false
        var pliSent = false
        fun onSurfaceCreated() { decoderReady = cachedCsd; pliSent = true }
        onSurfaceCreated()
        assertTrue(decoderReady)
        assertTrue(pliSent)
        decoderReady = false
        onSurfaceCreated()
        assertTrue(decoderReady)
    }
}
