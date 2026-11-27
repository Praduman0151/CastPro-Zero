package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class AudioPlaybackQueueTests {
    private fun frame(session: Long, seq: Long, ts: Long) =
        AudioFrame(session, seq, ts, byteArrayOf((seq and 0xff).toByte()))

    @Test fun lateFrameIsDropped() {
        val q = AudioPlaybackQueue(77)
        assertEquals(1, q.accept(frame(77, 10, 1_000), 1_000).size)
        assertTrue(q.accept(frame(77, 9, 900), 1_001).isEmpty())
    }

    @Test fun duplicateSequenceIsDropped() {
        val q = AudioPlaybackQueue(77)
        q.accept(frame(77, 10, 1_000), 1_000)
        assertTrue(q.accept(frame(77, 10, 1_000), 1_001).isEmpty())
    }

    @Test fun bufferIsCappedAtEightFrames() {
        val q = AudioPlaybackQueue(77)
        var result = emptyList<AudioFrame>()
        for (i in 1L..9L) result = q.accept(frame(77, i, i * 1_000_000), i * 1_000_000)
        assertEquals(8, result.size)
        assertEquals(2L, result.first().sequence)
        assertEquals(9L, result.last().sequence)
    }

    @Test fun expiredFrameIsDropped() {
        val q = AudioPlaybackQueue(77)
        q.accept(frame(77, 1, 1_000), 1_000)
        // 201 ms after capture, beyond the 200 ms freshness window.
        assertTrue(q.accept(frame(77, 2, 1_000), 201_000_001).isEmpty())
    }

    @Test fun wrongSessionIsRejected() {
        val q = AudioPlaybackQueue(77)
        assertTrue(q.accept(frame(78, 1, 1_000), 1_000).isEmpty())
    }

    @Test fun resetAllowsSequencesAgain() {
        val q = AudioPlaybackQueue(77)
        q.accept(frame(77, 10, 1_000), 1_000)
        q.reset()
        assertEquals(1, q.accept(frame(77, 5, 2_000), 2_000).size)
    }
}
