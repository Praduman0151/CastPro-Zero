package com.castpro.zero.phone.capture

import com.castpro.zero.protocol.SenderStats

/** Keeps the last measured snapshot so PLI/RTT events cannot publish placeholder samples. */
class SenderTelemetryState {
    @Volatile private var last: SenderStats? = null

    @Synchronized fun publishMeasured(stats: SenderStats): SenderStats {
        last = stats
        return stats
    }

    @Synchronized fun onPli(count: Long): SenderStats? =
        last?.copy(pliCount = count)?.also { last = it }

    @Synchronized fun onRtt(ms: Long): SenderStats? =
        last?.copy(rttMs = ms)?.also { last = it }

    @Synchronized fun snapshot(): SenderStats? = last

    @Synchronized fun reset() { last = null }
}
