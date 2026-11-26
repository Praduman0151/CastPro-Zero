package com.castpro.zero.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val CONTROL_HEADER_BYTES = 24
const val MAX_CONTROL_PAYLOAD = 65_536
private val CONTROL_MAGIC = byteArrayOf(0x43, 0x50, 0x5A, 0x30)

enum class ControlType(val id: Int) {
    HELLO(1), HELLO_ACK(2), PAIR_CONFIRM(3), STREAM_CONFIG(16), VIDEO_CSD(17),
    PING(32), PONG(33), PLI(34), STOP(35), RTT_REPORT(36), ERROR(127);
    companion object {
        fun fromId(id: Int): ControlType = entries.firstOrNull { it.id == id }
            ?: throw ProtocolException("Unknown control message type: $id")
    }
}

data class ControlFrame(val type: ControlType, val flags: Int = 0, val sequence: Long,
                        val sessionId: Long, val payload: ByteArray = byteArrayOf(), val version: Int = 1)

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ControlFraming {
    fun write(output: OutputStream, frame: ControlFrame) {
        require(frame.payload.size <= MAX_CONTROL_PAYLOAD) { "Control payload exceeds limit" }
        require(frame.flags in 0..0xffff && frame.sequence in 0..0xffff_ffffL)
        val h = ByteBuffer.allocate(CONTROL_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        h.put(CONTROL_MAGIC).put(frame.version.toByte()).put(frame.type.id.toByte())
            .putShort(frame.flags.toShort()).putInt(frame.payload.size).putInt(frame.sequence.toInt()).putLong(frame.sessionId)
        output.write(h.array()); output.write(frame.payload); output.flush()
    }

    /** Returns null only when EOF occurs before any byte of a new header. */
    fun read(input: InputStream): ControlFrame? {
        val header = readFullyOrEof(input, CONTROL_HEADER_BYTES) ?: return null
        val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4); b.get(magic)
        if (!magic.contentEquals(CONTROL_MAGIC)) throw ProtocolException("Invalid CPZ0 magic")
        val version = b.get().toInt() and 0xff
        if (version != 1) throw ProtocolException("Unsupported protocol version: $version")
        val type = ControlType.fromId(b.get().toInt() and 0xff)
        val flags = b.short.toInt() and 0xffff
        val length = b.int
        if (length !in 0..MAX_CONTROL_PAYLOAD) throw ProtocolException("Invalid payload length: $length")
        val sequence = b.int.toLong() and 0xffff_ffffL
        val sessionId = b.long
        return ControlFrame(type, flags, sequence, sessionId, readFully(input, length), version)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray =
        if (size == 0) byteArrayOf() else readFullyOrEof(input, size) ?: throw EOFException("Unexpected EOF in payload")

    private fun readFullyOrEof(input: InputStream, size: Int): ByteArray? {
        val result = ByteArray(size); var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) {
                if (offset == 0) return null
                throw EOFException("Truncated stream: $offset of $size bytes")
            }
            if (count > 0) offset += count
        }
        return result
    }
}
