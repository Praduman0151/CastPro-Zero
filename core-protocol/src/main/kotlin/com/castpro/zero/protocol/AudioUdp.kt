package com.castpro.zero.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val AUDIO_HEADER_BYTES = 32
const val MAX_AUDIO_PAYLOAD_BYTES = MAX_UDP_DATAGRAM_BYTES - AUDIO_HEADER_BYTES
const val MAX_SEALED_AUDIO_PAYLOAD_BYTES = MAX_AUDIO_PAYLOAD_BYTES - PacketSeal.TAG_BYTES
private val AUDIO_MAGIC = byteArrayOf(0x43, 0x41) // "CA"

/**
 * One authenticated UDP datagram of PCM16 device audio. Supports optional AEAD
 * authentication/encryption using the session's derived audioKey without exceeding 1360 bytes.
 */
data class AudioFrame(val sessionId: Long, val sequence: Long, val timestampNs: Long, val pcm: ByteArray) {
    fun encode(audioKey: ByteArray? = null): ByteArray {
        val encrypted = audioKey != null
        val maxPcm = if (encrypted) MAX_SEALED_AUDIO_PAYLOAD_BYTES else MAX_AUDIO_PAYLOAD_BYTES
        require(sessionId != 0L)
        require(sequence in 0..Long.MAX_VALUE)
        require(timestampNs in 0..Long.MAX_VALUE)
        require(pcm.isNotEmpty() && pcm.size <= maxPcm)
        val wireLen = if (encrypted) pcm.size + PacketSeal.TAG_BYTES else pcm.size
        val out = ByteArray(AUDIO_HEADER_BYTES + wireLen)
        val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.put(AUDIO_MAGIC).put(1.toByte()).put((if (encrypted) 1 else 0).toByte())
            .putLong(sessionId).putLong(sequence).putLong(timestampNs)
            .putShort(wireLen.toShort()).putShort(0)
        if (encrypted) {
            PacketSeal.sealInto(
                audioKey!!,
                PacketSeal.audioNonce(sequence),
                out, 0, AUDIO_HEADER_BYTES,
                pcm, 0, pcm.size,
                out, AUDIO_HEADER_BYTES
            )
        } else {
            pcm.copyInto(out, AUDIO_HEADER_BYTES)
        }
        return out
    }

    companion object {
        fun decode(packet: ByteArray, packetLength: Int = packet.size, audioKey: ByteArray? = null): AudioFrame {
            if (packetLength !in (AUDIO_HEADER_BYTES + 1)..minOf(MAX_UDP_DATAGRAM_BYTES, packet.size)) {
                throw ProtocolException("Invalid audio UDP size")
            }
            val b = ByteBuffer.wrap(packet, 0, packetLength).order(ByteOrder.BIG_ENDIAN)
            if (b.get() != AUDIO_MAGIC[0] || b.get() != AUDIO_MAGIC[1]) {
                throw ProtocolException("Invalid CA magic")
            }
            val version = b.get().toInt() and 255
            if (version != 1) throw ProtocolException("Unsupported audio version")
            val flags = b.get().toInt() and 255
            val session = b.long
            val sequence = b.long
            val timestamp = b.long
            val length = b.short.toInt() and 65535
            b.short // reserved
            if (session == 0L || sequence < 0 || timestamp < 0 || length < 1 || length != b.remaining()) {
                throw ProtocolException("Invalid audio frame metadata")
            }
            val pcm = if (audioKey != null) {
                if ((flags and 1) == 0) throw ProtocolException("Unencrypted audio rejected on encrypted session")
                PacketSeal.open(
                    audioKey,
                    PacketSeal.audioNonce(sequence),
                    packet,
                    packet,
                    length,
                    AUDIO_HEADER_BYTES,
                    aadOffset = 0,
                    aadLength = AUDIO_HEADER_BYTES
                )
            } else {
                packet.copyOfRange(AUDIO_HEADER_BYTES, AUDIO_HEADER_BYTES + length)
            }
            return AudioFrame(session, sequence, timestamp, pcm)
        }
    }
}
