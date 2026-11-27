package com.castpro.zero.tv

import com.castpro.zero.protocol.AudioFrame
import org.junit.Assert.*
import org.junit.Test

class TvEngineAndAvSyncTests {
    @Test fun orderedAudioBufferReordersOutOfOrderBurstsAndTracksPartialWriteOffset() {
        val buf = OrderedAudioPlaybackBuffer(sessionId = 99L, maxFrames = 12, expiryNs = 150_000_000L)
        // Burst packets 1, 3, 2 arrive slightly out of order
        buf.offer(AudioFrame(99L, 1L, 10_000_000L, byteArrayOf(1, 2, 3, 4)), estimatedNowOnSenderNs = 12_000_000L)
        buf.offer(AudioFrame(99L, 3L, 24_000_000L, byteArrayOf(9, 10, 11, 12)), estimatedNowOnSenderNs = 26_000_000L)
        buf.offer(AudioFrame(99L, 2L, 17_000_000L, byteArrayOf(5, 6, 7, 8)), estimatedNowOnSenderNs = 19_000_000L)

        // Frame 1 partial write (writes 2 of 4 bytes) must keep bytes [2, 3] for retry — NEVER byte 0 again
        val first = buf.peekPlayable(estimatedNowOnSenderNs = 20_000_000L)!!
        assertEquals(1L, first.frame.sequence)
        assertEquals(0, first.offset)
        buf.advanceHead(writtenBytes = 2)

        val retry = buf.peekPlayable(estimatedNowOnSenderNs = 21_000_000L)!!
        assertEquals(1L, retry.frame.sequence)
        assertEquals(2, retry.offset)
        assertEquals(2, retry.remaining)
        buf.advanceHead(writtenBytes = 2)

        // Next must be reordered sequence 2, then 3
        val second = buf.peekPlayable(estimatedNowOnSenderNs = 22_000_000L)!!
        assertEquals(2L, second.frame.sequence)
        buf.advanceHead(writtenBytes = 4)

        val third = buf.peekPlayable(estimatedNowOnSenderNs = 27_000_000L)!!
        assertEquals(3L, third.frame.sequence)
        buf.advanceHead(writtenBytes = 4)

        assertNull(buf.peekPlayable(estimatedNowOnSenderNs = 28_000_000L))
    }

    @Test fun orderedAudioBufferExpiresGenuinelyStalePacketsUsingSenderTimeline() {
        val buf = OrderedAudioPlaybackBuffer(sessionId = 5L, maxFrames = 12, expiryNs = 100_000_000L)
        buf.offer(AudioFrame(5L, 1L, 10_000_000L, byteArrayOf(1, 2, 3, 4)), estimatedNowOnSenderNs = 15_000_000L)
        buf.offer(AudioFrame(5L, 2L, 200_000_000L, byteArrayOf(5, 6, 7, 8)), estimatedNowOnSenderNs = 210_000_000L)

        // At sender time 220ms, frame 1 (captured at 10ms) is 210ms old (> 100ms expiry) and must be dropped!
        val playable = buf.peekPlayable(estimatedNowOnSenderNs = 220_000_000L)!!
        assertEquals(2L, playable.frame.sequence)
        assertEquals(1L, buf.expiredCount)
    }

    @Test fun clockOffsetEstimatorTracksMinOneWayOffsetWithoutDriftSpikes() {
        val est = ClockOffsetEstimator()
        // True offset = TV_clock(10_000ms) - Phone_clock(1_000ms) = +9_000ms, plus network jitter
        est.observe(senderCaptureNs = 1_000_000_000L, receiverArrivalNs = 10_004_000_000L) // +4ms transit
        est.observe(senderCaptureNs = 1_016_000_000L, receiverArrivalNs = 10_046_000_000L) // +30ms Wi-Fi jitter spike
        est.observe(senderCaptureNs = 1_033_000_000L, receiverArrivalNs = 10_036_000_000L) // +3ms transit
        val nowSender = est.toSenderTimeNs(receiverNowNs = 10_050_000_000L)
        // Minimum observed offset is +9_003_000_000ns -> estimated sender time = 1_047ms
        assertEquals(1_047_000_000L, nowSender)
    }

    @Test fun avSyncControllerSignalsDropAheadOrHoldWithinTwentyMsWindow() {
        val sync = AvSyncController(maxDriftNs = 25_000_000L)
        assertEquals(AvSyncAction.PLAY_NORMAL, sync.evaluate(videoCaptureNs = 1_000_000_000L, audioPlayheadCaptureNs = 985_000_000L))
        // Audio lagging >25ms behind video (1000ms vs 940ms = 60ms behind) -> drop old queued audio
        assertEquals(AvSyncAction.DROP_STALE_AUDIO, sync.evaluate(videoCaptureNs = 1_000_000_000L, audioPlayheadCaptureNs = 940_000_000L))
        // Audio >25ms ahead of video -> briefly hold playout
        assertEquals(AvSyncAction.HOLD_AUDIO, sync.evaluate(videoCaptureNs = 1_000_000_000L, audioPlayheadCaptureNs = 1_040_000_000L))
    }

    @Test fun vsyncFpsTrackerDividesByExactElapsedSecondsAndNeverInflatesAboveRenderEvents() {
        val tracker = VsyncFpsTracker()
        // Even if telemetry wakes up 1500ms late with 90 rendered frames at 60Hz, FPS must be 90 / 1.5 = 60!
        repeat(90) { i ->
            tracker.onFrameRendered(1_000_000_000L + i * 16_666_667L)
        }
        val fps = tracker.sampleFps(windowStartNs = 1_000_000_000L, windowEndNs = 2_500_000_000L)
        assertEquals(60L, fps)
    }

    @Test fun tvVideoFrameQueueDropsStaleNonKeyFramesOnOverflowAndFlagsDiscontinuity() {
        val q = TvVideoFrameQueue(capacity = 2)
        val f1 = com.castpro.zero.protocol.ReassembledFrame(1, 4, byteArrayOf(1), 100L)
        val f2 = com.castpro.zero.protocol.ReassembledFrame(2, 0, byteArrayOf(2), 200L)
        val f3 = com.castpro.zero.protocol.ReassembledFrame(3, 0, byteArrayOf(3), 300L)
        assertFalse(q.offer(f1).overflowed)
        assertFalse(q.offer(f2).overflowed)
        // Third frame overflows capacity=2 and isn't IDR -> flushes stale P-frames & marks discontinuity
        val overflow = q.offer(f3)
        assertTrue(overflow.overflowed)
        assertTrue(overflow.discontinuityRequired)
        assertNull(q.poll(1))
    }

    @Test fun rttTelemetryStateHandlesConcurrentResetsCleanly() {
        val s = RttTelemetryState()
        s.recordPing(1000L, 1L)
        s.acceptPong(com.castpro.zero.protocol.ControlMessage.Pong(1000L, 1L), 16_000_000L)
        assertEquals(15L, s.rttMs)
        s.reset()
        assertEquals(-1L, s.rttMs)
    }
}
