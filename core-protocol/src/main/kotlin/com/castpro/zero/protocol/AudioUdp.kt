package com.castpro.zero.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val AUDIO_HEADER_BYTES = 32
const val MAX_AUDIO_PAYLOAD_BYTES = MAX_UDP_DATAGRAM_BYTES - AUDIO_HEADER_BYTES
private val AUDIO_MAGIC = byteArrayOf(0x43, 0x41) // "CA"

/**
 * One authenticated UDP datagram of PCM16 device audio. A 20 ms stereo frame at
 * 48 kHz is 3840 bytes, so the capture side splits it into consecutive sequence
 * numbers that all share the same capture timestamp; the TV plays them in order.
 */
data class AudioFrame(val sessionId: Long, val sequence: Long, val timestampNs: Long, val pcm: ByteArray) {
    fun encode(): ByteArray {
        require(sessionId in 0..Long.MAX_VALUE)
        require(sequence in 0..Long.MAX_VALUE)
        require(timestampNs in 0..Long.MAX_VALUE)
        require(pcm.isNotEmpty() && pcm.size <= MAX_AUDIO_PAYLOAD_BYTES)
        val b = ByteBuffer.allocate(AUDIO_HEADER_BYTES + pcm.size).order(ByteOrder.BIG_ENDIAN)
        b.put(AUDIO_MAGIC).put(1.toByte()).put(0.toByte())
            .putLong(sessionId).putLong(sequence).putLong(timestampNs)
            .putShort(pcm.size.toShort()).putShort(0)
            .put(pcm)
        return b.array()
    }

    companion object {
        fun decode(packet: ByteArray): AudioFrame {
            if (packet.size !in (AUDIO_HEADER_BYTES + 1)..MAX_UDP_DATAGRAM_BYTES) {
                throw ProtocolException("Invalid audio UDP size")
            }
            val b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            if (b.get() != AUDIO_MAGIC[0] || b.get() != AUDIO_MAGIC[1]) {
                throw ProtocolException("Invalid CA magic")
            }
            val version = b.get().toInt() and 255
            if (version != 1) throw ProtocolException("Unsupported audio version")
            b.get() // reserved flags
            val session = b.long
            val sequence = b.long
            val timestamp = b.long
            val length = b.short.toInt() and 65535
            b.short // reserved
            if (session < 0 || sequence < 0 || timestamp < 0 || length < 1 || length != b.remaining()) {
                throw ProtocolException("Invalid audio frame metadata")
            }
            return AudioFrame(session, sequence, timestamp, ByteArray(length).also(b::get))
        }
    }
}
