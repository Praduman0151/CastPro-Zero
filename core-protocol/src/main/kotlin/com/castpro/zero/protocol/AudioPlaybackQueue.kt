package com.castpro.zero.protocol

/**
 * Small authenticated jitter buffer for audio datagrams. It exposes the current
 * playable frames in sequence order; the caller writes only sequences it has not
 * written yet. Late, duplicate, expired and foreign-session datagrams are dropped
 * without disturbing the playout order.
 */
class AudioPlaybackQueue(
    private val sessionId: Long,
    private val capacity: Int = 8,
    private val expiryNs: Long = 200_000_000L
) {
    private val buffer = ArrayDeque<AudioFrame>()
    private var newestSequence = -1L
    var droppedStale: Long = 0L
        private set
    var droppedExpired: Long = 0L
        private set
    var droppedEvicted: Long = 0L
        private set

    @Synchronized
    fun accept(frame: AudioFrame, nowNs: Long): List<AudioFrame> {
        if (frame.sessionId != sessionId || frame.sequence <= newestSequence) {
            droppedStale++
            return emptyList()
        }
        if (nowNs - frame.timestampNs > expiryNs) {
            droppedExpired++
            return emptyList()
        }
        newestSequence = frame.sequence
        buffer.addLast(frame)
        while (buffer.size > capacity) {
            buffer.removeFirst()
            droppedEvicted++
        }
        return buffer.toList()
    }

    @Synchronized
    fun reset() {
        buffer.clear()
        newestSequence = -1L
        droppedStale = 0L
        droppedExpired = 0L
        droppedEvicted = 0L
    }
}
