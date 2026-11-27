package com.castpro.zero.phone.capture

import com.castpro.zero.protocol.MAX_AUDIO_PAYLOAD_BYTES

/** Pure policy for device-audio capture; kept free of Android APIs so it is JVM-testable. */
object AudioCapturePolicy {
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    const val BIT_DEPTH = 16
    const val ENCODING_PCM_16BIT = 1
    const val FRAME_BYTES = CHANNEL_COUNT * (BIT_DEPTH / 8)
    val CHUNK_BYTES = (MAX_AUDIO_PAYLOAD_BYTES / FRAME_BYTES) * FRAME_BYTES

    fun shouldCapture(permissionGranted: Boolean, projectionAvailable: Boolean): Boolean =
        permissionGranted && projectionAvailable

    /** System.nanoTime() may start negative; the wire timestamp must not. */
    fun safeTimestamp(monotonicNs: Long): Long = monotonicNs.coerceAtLeast(0L)

    /** Never report audio as on until startRecording() has actually succeeded. */
    fun shouldReportActive(startRecordingSucceeded: Boolean): Boolean = startRecordingSucceeded

    /** Splits interleaved PCM16 stereo into whole-sample datagrams without copying empty tails. */
    fun chunkPcm(pcm: ByteArray): List<ByteArray> {
        require(pcm.isNotEmpty() && pcm.size % FRAME_BYTES == 0) { "PCM must be non-empty and frame-aligned" }
        val result = ArrayList<ByteArray>((pcm.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + CHUNK_BYTES, pcm.size)
            result.add(pcm.copyOfRange(offset, end))
            offset = end
        }
        return result
    }
}
