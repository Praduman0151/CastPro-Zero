package com.castpro.zero.phone

import com.castpro.zero.phone.capture.EncoderFormatPolicy
import com.castpro.zero.phone.capture.EncoderTier
import org.junit.Assert.*
import org.junit.Test

class EncoderFormatPolicyTests {
    @Test fun primaryGamingTierEnablesLowLatencyRealtime60FpsCapAndCbr() {
        val tiers = EncoderFormatPolicy.specsFor(1920, 1080, 20_000_000, 60)
        assertTrue(tiers.size >= 3)
        val primary = tiers.first()
        assertEquals(EncoderTier.GAMING_LOW_LATENCY, primary.tier)
        assertEquals(1920, primary.width)
        assertEquals(1080, primary.height)
        assertEquals(20_000_000, primary.bitrate)
        assertEquals(60, primary.fps)
        assertEquals(60f, primary.maxFpsToEncoder!!, 0.01f)
        assertEquals(0, primary.priority)
        assertEquals(1, primary.lowLatency)
        assertEquals(1, primary.prependHeaderToSyncFrames)
        assertEquals(0, primary.maxBFrames)
        assertTrue(primary.operatingRate!! >= 60)
        assertTrue(primary.cbrMode)
        assertTrue(primary.includeColorMetadata)
    }

    @Test fun fallbackTiersProgressivelyRelaxVendorKeysDownToBaseline() {
        val tiers = EncoderFormatPolicy.specsFor(1920, 1080, 20_000_000, 60)
        val last = tiers.last()
        assertEquals(EncoderTier.BASELINE, last.tier)
        assertNull(last.lowLatency)
        assertNull(last.operatingRate)
        assertNull(last.maxFpsToEncoder)
        assertNull(last.prependHeaderToSyncFrames)
        assertFalse(last.cbrMode)
        assertFalse(last.includeColorMetadata)
        assertEquals(0, last.maxBFrames)
    }

    @Test fun alignmentRoundsUpOddDimensionsToCodecAlignment() {
        val a1 = EncoderFormatPolicy.alignDimensions(1920, 1080, 16, 16)
        assertEquals(1920, a1.width)
        assertEquals(1088, a1.height)
        val a2 = EncoderFormatPolicy.alignDimensions(1280, 720, 2, 2)
        assertEquals(1280, a2.width)
        assertEquals(720, a2.height)
        val a3 = EncoderFormatPolicy.alignDimensions(1081, 2401, 16, 8)
        assertEquals(1088, a3.width)
        assertEquals(2408, a3.height)
    }
}
