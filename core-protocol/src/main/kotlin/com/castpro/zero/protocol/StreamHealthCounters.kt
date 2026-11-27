package com.castpro.zero.protocol

const val PLI_REASON_REASSEMBLY = 1
const val PLI_REASON_DECODER_SETUP = 2
const val PLI_REASON_SURFACE = 6

data class HealthSnapshot(
    val reassemblyDrops: Long,
    val backpressureDrops: Long,
    val setupFailures: Long,
    val pliCount: Long,
    val pliReasons: Map<Int, Int>
)

/**
 * Separates genuine packet loss from decoder backpressure. Only loss and codec
 * setup failures request a PLI; a busy decoder is handled by dropping stale
 * frames quietly, which prevents the PLI -> CSD replay -> IDR feedback loop.
 */
class StreamHealthCounters {
    private var reassemblyDrops = 0L
    private var backpressureDrops = 0L
    private var setupFailures = 0L
    private var pliCount = 0L
    private val pliReasons = LinkedHashMap<Int, Int>()

    @Synchronized fun onReassemblyDrop() {
        reassemblyDrops++
    }

    @Synchronized fun onReassemblyLoss() {
        onReassemblyDrop()
        onPli(PLI_REASON_REASSEMBLY)
    }

    @Synchronized fun onDecoderBackpressure() {
        backpressureDrops++
    }

    @Synchronized fun onSetupFailureOnly() {
        setupFailures++
    }

    @Synchronized fun onDecoderSetupFailure() {
        onSetupFailureOnly()
        onPli(PLI_REASON_DECODER_SETUP)
    }

    @Synchronized fun onPli(reason: Int) {
        pliCount++
        pliReasons[reason] = (pliReasons[reason] ?: 0) + 1
    }

    @Synchronized fun snapshot(): HealthSnapshot =
        HealthSnapshot(reassemblyDrops, backpressureDrops, setupFailures, pliCount, pliReasons.toMap())

    @Synchronized fun reset() {
        reassemblyDrops = 0L
        backpressureDrops = 0L
        setupFailures = 0L
        pliCount = 0L
        pliReasons.clear()
    }
}
