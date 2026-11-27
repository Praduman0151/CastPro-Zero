package com.castpro.zero.tv

import com.castpro.zero.protocol.AudioFrame
import java.util.TreeMap

/**
 * Estimates clock offset (tvTimeNs - phoneTimeNs) using minimum transit delay so
 * sender timestamps remain valid on the receiver across Wi-Fi jitter spikes while
 * tracking long-term crystal skew across 60-minute sessions.
 */
class ClockOffsetEstimator(
    private val windowSamples: Int = 600
) {
    private var currentMinOffsetNs = Long.MAX_VALUE
    private var prevMinOffsetNs = Long.MAX_VALUE
    private var samplesInWindow = 0
    @Volatile private var hasEstimate = false

    @Synchronized
    fun observe(senderCaptureNs: Long, receiverArrivalNs: Long) {
        val sample = receiverArrivalNs - senderCaptureNs
        if (sample < currentMinOffsetNs) {
            currentMinOffsetNs = sample
        }
        hasEstimate = true
        samplesInWindow++
        if (samplesInWindow >= windowSamples) {
            prevMinOffsetNs = currentMinOffsetNs
            currentMinOffsetNs = sample
            samplesInWindow = 1
        }
    }

    @Synchronized
    fun offsetNs(): Long? {
        if (!hasEstimate) return null
        return if (prevMinOffsetNs == Long.MAX_VALUE) {
            currentMinOffsetNs
        } else {
            minOf(currentMinOffsetNs, prevMinOffsetNs)
        }
    }

    fun toSenderTimeNs(receiverNowNs: Long): Long {
        val off = offsetNs() ?: return receiverNowNs
        return receiverNowNs - off
    }

    @Synchronized
    fun reset() {
        currentMinOffsetNs = Long.MAX_VALUE
        prevMinOffsetNs = Long.MAX_VALUE
        samplesInWindow = 0
        hasEstimate = false
    }
}

data class PlayableAudioChunk(val frame: AudioFrame, val offset: Int) {
    val remaining: Int get() = frame.pcm.size - offset
}

/**
 * Out-of-order-tolerant, consuming audio jitter buffer with partial-write offset tracking.
 * Prevents duplicate PCM playback on partial AudioTrack.write() and expires genuinely stale
 * chunks using the sender's monotonic capture timeline.
 */
class OrderedAudioPlaybackBuffer(
    private val sessionId: Long,
    private val maxFrames: Int = 16,
    private val expiryNs: Long = 150_000_000L
) {
    private val pending = TreeMap<Long, AudioFrame>()
    private var headOffset = 0
    private var lastConsumedSequence = -1L

    var staleDropped: Long = 0L
        private set
    var expiredCount: Long = 0L
        private set
    var evictedCount: Long = 0L
        private set

    @Synchronized
    fun offer(frame: AudioFrame, estimatedNowOnSenderNs: Long): Boolean {
        if (frame.sessionId != sessionId || frame.sequence <= lastConsumedSequence) {
            staleDropped++
            return false
        }
        if (estimatedNowOnSenderNs - frame.timestampNs > expiryNs) {
            expiredCount++
            return false
        }
        if (pending.containsKey(frame.sequence)) {
            staleDropped++
            return false
        }
        pending[frame.sequence] = frame
        while (pending.size > maxFrames) {
            val firstKey = pending.firstKey()
            pending.remove(firstKey)
            headOffset = 0
            if (firstKey > lastConsumedSequence) {
                lastConsumedSequence = firstKey
            }
            evictedCount++
        }
        return true
    }

    @Synchronized
    fun peekPlayable(estimatedNowOnSenderNs: Long): PlayableAudioChunk? {
        while (pending.isNotEmpty()) {
            val firstEntry = pending.firstEntry() ?: return null
            val frame = firstEntry.value
            if (headOffset == 0 && estimatedNowOnSenderNs - frame.timestampNs > expiryNs) {
                pending.pollFirstEntry()
                lastConsumedSequence = frame.sequence
                expiredCount++
                continue
            }
            return PlayableAudioChunk(frame, headOffset)
        }
        return null
    }

    @Synchronized
    fun advanceHead(writtenBytes: Int) {
        if (writtenBytes <= 0 || pending.isEmpty()) return
        val firstEntry = pending.firstEntry() ?: return
        val frame = firstEntry.value
        val nextOffset = headOffset + writtenBytes
        if (nextOffset >= frame.pcm.size) {
            pending.pollFirstEntry()
            lastConsumedSequence = frame.sequence
            headOffset = 0
        } else {
            headOffset = nextOffset
        }
    }

    @Synchronized
    fun dropHead(): Boolean {
        val dropped = pending.pollFirstEntry() ?: return false
        headOffset = 0
        if (dropped.value.sequence > lastConsumedSequence) {
            lastConsumedSequence = dropped.value.sequence
        }
        expiredCount++
        return true
    }

    @Synchronized
    fun queuedFrames(): Int = pending.size

    @Synchronized
    fun clear() {
        pending.clear()
        headOffset = 0
        lastConsumedSequence = -1L
    }
}

enum class AvSyncAction { PLAY_NORMAL, DROP_STALE_AUDIO, HOLD_AUDIO }

/** Keeps audio playback locked within +/- maxDriftNs of the rendered video timeline. */
class AvSyncController(private val maxDriftNs: Long = 25_000_000L) {
    fun evaluate(videoCaptureNs: Long, audioPlayheadCaptureNs: Long): AvSyncAction {
        if (videoCaptureNs <= 0L || audioPlayheadCaptureNs <= 0L) return AvSyncAction.PLAY_NORMAL
        val delta = videoCaptureNs - audioPlayheadCaptureNs
        return when {
            delta > maxDriftNs -> AvSyncAction.DROP_STALE_AUDIO
            delta < -maxDriftNs -> AvSyncAction.HOLD_AUDIO
            else -> AvSyncAction.PLAY_NORMAL
        }
    }
}
