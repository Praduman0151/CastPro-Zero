package com.castpro.zero.phone.capture

import com.castpro.zero.protocol.MAX_AUDIO_PAYLOAD_BYTES
import com.castpro.zero.protocol.MAX_UDP_DATAGRAM_BYTES
import org.junit.Assert.*
import org.junit.Test

class AudioCapturePolicyTests {
    @Test fun captureRequiresBothPermissionAndProjection() {
        assertTrue(AudioCapturePolicy.shouldCapture(true, true))
        assertFalse(AudioCapturePolicy.shouldCapture(false, true))
        assertFalse(AudioCapturePolicy.shouldCapture(true, false))
        assertFalse(AudioCapturePolicy.shouldCapture(false, false))
    }

    @Test fun twentyMillisecondsOfStereoIsSplitIntoUdpSizedChunks() {
        val pcm = ByteArray(3840) { it.toByte() }
        val chunks = AudioCapturePolicy.chunkPcm(pcm)
        assertEquals(3, chunks.size)
        assertEquals(listOf(1328, 1328, 1184), chunks.map { it.size })
        assertTrue(chunks.all { it.size <= MAX_AUDIO_PAYLOAD_BYTES && it.size % 4 == 0 })
        assertTrue(chunks.fold(ByteArray(0)) { acc, part -> acc + part }.contentEquals(pcm))
        assertTrue(chunks.all { it.size + 32 <= MAX_UDP_DATAGRAM_BYTES })
    }

    @Test fun monotonicClockIsMadeSafeForTheWire() {
        assertEquals(0L, AudioCapturePolicy.safeTimestamp(-1L))
        assertEquals(123L, AudioCapturePolicy.safeTimestamp(123L))
    }

    @Test fun activeStateRequiresSuccessfulStartRecording() {
        assertFalse(AudioCapturePolicy.shouldReportActive(false))
        assertTrue(AudioCapturePolicy.shouldReportActive(true))
    }

    @Test fun emptyOrMisalignedPcmIsRejected() {
        try {
            AudioCapturePolicy.chunkPcm(ByteArray(0))
            fail("Expected empty PCM rejection")
        } catch (_: IllegalArgumentException) {}
        try {
            AudioCapturePolicy.chunkPcm(ByteArray(5))
            fail("Expected frame-alignment rejection")
        } catch (_: IllegalArgumentException) {}
    }
}
