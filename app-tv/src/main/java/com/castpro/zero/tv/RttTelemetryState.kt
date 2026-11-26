package com.castpro.zero.tv

import com.castpro.zero.protocol.ControlMessage

/** Correlates TV-originated TCP PINGs with matching PONGs; stale responses are ignored. */
class RttTelemetryState {
    @Volatile var rttMs: Long = -1L
        private set
    private var expectedTimestampNs: Long = -1L
    private var expectedSequence: Long = -1L

    @Synchronized fun recordPing(timestampNs: Long, sequence: Long) {
        expectedTimestampNs = timestampNs
        expectedSequence = sequence
    }

    @Synchronized fun acceptPong(message: ControlMessage.Pong, nowNs: Long): Boolean {
        if (message.timestampNs != expectedTimestampNs || message.pingSequence != expectedSequence) return false
        rttMs = (nowNs - expectedTimestampNs).coerceAtLeast(0L) / 1_000_000L
        expectedTimestampNs = -1L
        expectedSequence = -1L
        return true
    }

    fun acceptPhoneReport(value: Long) { rttMs = value.coerceAtLeast(0L) }

    @Synchronized fun reset() {
        rttMs = -1L
        expectedTimestampNs = -1L
        expectedSequence = -1L
    }
}
