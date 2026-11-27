package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class AudioProtocolTests {
    @Test fun audioConfigRoundTrips() {
        val value = ControlMessage.AudioConfig(48_000, 2, 16, 1)
        val decoded = ControlMessageCodec.decode(ControlMessageCodec.typeOf(value), ControlMessageCodec.encode(value))
        assertTrue(decoded is ControlMessage.AudioConfig)
        assertEquals(value, decoded)
    }

    @Test fun audioConfigRejectsWrongPayloadLength() {
        try {
            ControlMessageCodec.decode(ControlType.AUDIO_CONFIG, ByteArray(15))
            fail("Expected ProtocolException for short audio config")
        } catch (_: ProtocolException) {}
    }

    @Test fun audioFrameRoundTripsTwentyMillisecondsOfStereo() {
        // 20 ms * 48 kHz * 2 channels * 2 bytes = 3840 bytes -> 3 datagrams.
        val pcm = ByteArray(3840) { (it and 0xff).toByte() }
        val frames = listOf(
            AudioFrame(77, 10, 1_000_000L, pcm.copyOfRange(0, 1328)),
            AudioFrame(77, 11, 1_000_000L, pcm.copyOfRange(1328, 2656)),
            AudioFrame(77, 12, 1_000_000L, pcm.copyOfRange(2656, 3840))
        )
        frames.forEach { frame ->
            val encoded = frame.encode()
            assertTrue("datagram must fit UDP budget", encoded.size <= MAX_UDP_DATAGRAM_BYTES)
            val decoded = AudioFrame.decode(encoded)
            assertEquals(frame.sessionId, decoded.sessionId)
            assertEquals(frame.sequence, decoded.sequence)
            assertEquals(frame.timestampNs, decoded.timestampNs)
            assertArrayEquals(frame.pcm, decoded.pcm)
        }
    }

    @Test fun audioFrameRejectsWrongMagic() {
        val encoded = AudioFrame(1, 1, 100L, byteArrayOf(1, 2, 3)).encode()
        encoded[0] = 0x58
        try {
            AudioFrame.decode(encoded)
            fail("Expected ProtocolException for wrong magic")
        } catch (_: ProtocolException) {}
    }

    @Test fun audioFrameRejectsOversizedPayload() {
        try {
            AudioFrame(1, 1, 100L, ByteArray(MAX_AUDIO_PAYLOAD_BYTES + 1)).encode()
            fail("Expected IllegalArgumentException for oversized payload")
        } catch (_: IllegalArgumentException) {}
    }
}
