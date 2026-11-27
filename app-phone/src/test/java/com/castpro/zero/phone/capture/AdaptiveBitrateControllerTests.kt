package com.castpro.zero.phone.capture

import org.junit.Assert.*
import org.junit.Test

class AdaptiveBitrateControllerTests {
    @Test fun staysAt1080pCeilingUnderHealthyNetworkAndThermalConditions() {
        val abr = AdaptiveBitrateController(ceilingBitrateBps = 20_000_000)
        var t = 1_000_000_000L
        repeat(5) {
            t += 1_000_000_000L
            val d = abr.evaluate(HealthSample(nowNs = t, rttMs = 15, lossDelta = 0, backpressureDelta = 0, txCongestionDropsDelta = 0, thermalLevel = 0))
            assertEquals(20_000_000, d.targetBitrateBps)
            assertEquals(ResolutionProfile.FHD_1080P, d.targetProfile)
            assertFalse(d.bitrateChanged)
            assertFalse(d.resolutionChanged)
        }
    }

    @Test fun lossAndRttSpikesStepBitrateDownBeforeTouchingResolution() {
        val abr = AdaptiveBitrateController(ceilingBitrateBps = 20_000_000, minBitrateBps = 4_000_000)
        val d1 = abr.evaluate(HealthSample(nowNs = 3_000_000_000L, rttMs = 85, lossDelta = 5, backpressureDelta = 0, txCongestionDropsDelta = 0, thermalLevel = 0))
        assertTrue(d1.bitrateChanged)
        assertTrue(d1.targetBitrateBps < 20_000_000)
        assertEquals(ResolutionProfile.FHD_1080P, d1.targetProfile)

        // Repeated congestion past cooldown eventually steps down to 4 Mbps while holding 1080p until streak=3 at min
        var last = d1
        var now = 3_000_000_000L
        repeat(8) {
            now += 2_500_000_000L
            last = abr.evaluate(HealthSample(nowNs = now, rttMs = 90, lossDelta = 6, backpressureDelta = 2, txCongestionDropsDelta = 1, thermalLevel = 0))
        }
        assertEquals(4_000_000, last.targetBitrateBps)
        assertEquals(ResolutionProfile.HD_720P, last.targetProfile)
    }

    @Test fun severeThermalImmediatelyCapsBitrateAndFallsBackTo720p() {
        val abr = AdaptiveBitrateController(ceilingBitrateBps = 20_000_000, minBitrateBps = 4_000_000)
        val d = abr.evaluate(HealthSample(nowNs = 2_000_000_000L, rttMs = 12, lossDelta = 0, backpressureDelta = 0, txCongestionDropsDelta = 0, thermalLevel = 2))
        assertTrue(d.bitrateChanged)
        assertTrue(d.targetBitrateBps <= 10_000_000)
        assertTrue(d.resolutionChanged)
        assertEquals(ResolutionProfile.HD_720P, d.targetProfile)
    }

    @Test fun gradualRecoveryRestoresBitrateAndResolutionAfterSustainedStability() {
        val abr = AdaptiveBitrateController(ceilingBitrateBps = 20_000_000, minBitrateBps = 4_000_000)
        abr.evaluate(HealthSample(nowNs = 2_000_000_000L, rttMs = 10, lossDelta = 0, backpressureDelta = 0, txCongestionDropsDelta = 0, thermalLevel = 2))
        assertEquals(ResolutionProfile.HD_720P, abr.currentProfile())

        var now = 4_000_000_000L
        var recovered = false
        repeat(25) {
            now += 1_000_000_000L
            val d = abr.evaluate(HealthSample(nowNs = now, rttMs = 12, lossDelta = 0, backpressureDelta = 0, txCongestionDropsDelta = 0, thermalLevel = 0))
            if (d.targetProfile == ResolutionProfile.FHD_1080P && d.targetBitrateBps == 20_000_000) {
                recovered = true
            }
        }
        assertTrue("Expected full recovery to 1080p60 at ceiling bitrate after sustained clean samples", recovered)
    }
}
