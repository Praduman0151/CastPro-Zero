package com.castpro.zero.tv

import com.castpro.zero.protocol.HealthSnapshot

/** Pure receiver decisions: backpressure is never a reason to force a keyframe. */
object TvReceiverPolicy {
    fun pliRequiredForBackpressure(): Boolean = false

    /** Audio uses its own authenticated datagram channel adjacent to video. */
    fun audioPortFor(videoPort: Int): Int = videoPort + 2

    /** A zero/partial non-blocking write left PCM behind; retry instead of advancing. */
    fun shouldAdvanceSequence(writtenBytes: Int, frameBytes: Int): Boolean = writtenBytes == frameBytes

    /** Audio setup/bind/capture failures are isolated from the video cast. */
    fun audioFailureIsNonFatal(): Boolean = true

    fun isSupportedAudioConfig(sampleRateHz: Int, channelCount: Int, bitDepth: Int, encoding: Int): Boolean =
        sampleRateHz == 48_000 && channelCount == 2 && bitDepth == 16 && encoding == 1

    /** Compact HUD breakdown that distinguishes real loss from a busy decoder. */
    fun formatHealth(snapshot: HealthSnapshot): String =
        "loss=${snapshot.reassemblyDrops} busy=${snapshot.backpressureDrops} setup=${snapshot.setupFailures}"
}
