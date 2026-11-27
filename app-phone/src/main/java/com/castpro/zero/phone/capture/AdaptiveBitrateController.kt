package com.castpro.zero.phone.capture

enum class ResolutionProfile(val width: Int, val height: Int) {
    FHD_1080P(1920, 1080),
    HD_720P(1280, 720)
}

data class HealthSample(
    val nowNs: Long,
    val rttMs: Long,
    val lossDelta: Int,
    val backpressureDelta: Int,
    val txCongestionDropsDelta: Int,
    val thermalLevel: Int
)

data class BitrateDecision(
    val targetBitrateBps: Int,
    val targetProfile: ResolutionProfile,
    val bitrateChanged: Boolean,
    val resolutionChanged: Boolean
)

/**
 * Hysteresis adaptive bitrate & 720p60 fallback controller driven by receiver loss/backpressure,
 * RTT spikes, TX congestion, and PowerManager thermal state.
 */
class AdaptiveBitrateController(
    private val ceilingBitrateBps: Int,
    private val minBitrateBps: Int = 4_000_000,
    private val downCooldownNs: Long = 2_000_000_000L,
    private val upIntervalNs: Long = 4_000_000_000L
) {
    private var currentBitrateBps = ceilingBitrateBps
    private var currentProfile = ResolutionProfile.FHD_1080P
    private var lastDownNs = Long.MIN_VALUE
    private var lastUpNs = Long.MIN_VALUE
    private var minOverloadStreak = 0
    private var healthyStreak = 0

    @Synchronized fun currentBitrate(): Int = currentBitrateBps
    @Synchronized fun currentProfile(): ResolutionProfile = currentProfile

    @Synchronized
    fun evaluate(sample: HealthSample): BitrateDecision {
        val prevBitrate = currentBitrateBps
        val prevProfile = currentProfile

        val severeThermal = sample.thermalLevel >= 2
        val moderateThermal = sample.thermalLevel >= 1
        val networkCongested = sample.lossDelta >= 3 ||
            sample.backpressureDelta >= 3 ||
            sample.txCongestionDropsDelta >= 1 ||
            sample.rttMs >= 65

        if (severeThermal) {
            healthyStreak = 0
            val thermalCap = minOf(ceilingBitrateBps / 2, 10_000_000).coerceAtLeast(minBitrateBps)
            currentBitrateBps = minOf(currentBitrateBps, thermalCap)
            currentProfile = ResolutionProfile.HD_720P
            lastDownNs = sample.nowNs
            return BitrateDecision(
                currentBitrateBps, currentProfile,
                currentBitrateBps != prevBitrate,
                currentProfile != prevProfile
            )
        }

        if (networkCongested || moderateThermal) {
            healthyStreak = 0
            val canStepDown = lastDownNs == Long.MIN_VALUE || sample.nowNs - lastDownNs >= downCooldownNs
            if (canStepDown) {
                val stepped = (currentBitrateBps * 7L / 10L).toInt().coerceAtLeast(minBitrateBps)
                if (stepped < currentBitrateBps) {
                    currentBitrateBps = stepped
                    minOverloadStreak = 0
                } else {
                    minOverloadStreak++
                }
                lastDownNs = sample.nowNs
                if (minOverloadStreak >= 3) {
                    currentProfile = ResolutionProfile.HD_720P
                }
            }
            return BitrateDecision(
                currentBitrateBps, currentProfile,
                currentBitrateBps != prevBitrate,
                currentProfile != prevProfile
            )
        }

        // Healthy network & thermal window
        minOverloadStreak = 0
        val clean = sample.lossDelta == 0 &&
            sample.backpressureDelta == 0 &&
            sample.txCongestionDropsDelta == 0 &&
            (sample.rttMs in 0..35 || sample.rttMs < 0)
        if (clean) {
            healthyStreak++
            if (currentProfile == ResolutionProfile.HD_720P && healthyStreak >= 15) {
                currentProfile = ResolutionProfile.FHD_1080P
            }
            val canStepUp = healthyStreak >= 8 &&
                (lastUpNs == Long.MIN_VALUE || sample.nowNs - lastUpNs >= upIntervalNs)
            if (canStepUp && currentBitrateBps < ceilingBitrateBps) {
                currentBitrateBps = (currentBitrateBps + 2_000_000).coerceAtMost(ceilingBitrateBps)
                lastUpNs = sample.nowNs
            }
        } else {
            healthyStreak = 0
        }

        return BitrateDecision(
            currentBitrateBps, currentProfile,
            currentBitrateBps != prevBitrate,
            currentProfile != prevProfile
        )
    }
}
