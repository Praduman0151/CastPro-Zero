package com.castpro.zero.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

const val VIDEO_HEADER_BYTES = 32
const val MAX_UDP_DATAGRAM_BYTES = 1360
const val MAX_VIDEO_PAYLOAD_BYTES = MAX_UDP_DATAGRAM_BYTES - VIDEO_HEADER_BYTES
const val MAX_SEALED_VIDEO_PLAINTEXT_BYTES = MAX_VIDEO_PAYLOAD_BYTES - PacketSeal.TAG_BYTES
const val DEFAULT_FRAME_DEADLINE_NS = 20_000_000L
const val PRODUCTION_FRAME_DEADLINE_NS = 45_000_000L
const val PLI_COALESCE_NS = 100_000_000L
private val VIDEO_MAGIC = byteArrayOf(0x43, 0x5a)

data class VideoFragment(
    val flags: Int,
    val sessionId: Long,
    val frameId: Long,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val payload: ByteArray,
    val captureTimestampNs: Long
) {
    fun encode(): ByteArray {
        require(
            flags in 0..255 &&
                frameId in 0..0xffff_ffffL &&
                fragmentCount in 1..65535 &&
                fragmentIndex in 0 until fragmentCount &&
                payload.size <= MAX_VIDEO_PAYLOAD_BYTES
        )
        val out = ByteArray(VIDEO_HEADER_BYTES + payload.size)
        val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.put(VIDEO_MAGIC).put(1.toByte()).put(flags.toByte()).putLong(sessionId).putInt(frameId.toInt())
            .putShort(fragmentIndex.toShort()).putShort(fragmentCount.toShort()).putShort(payload.size.toShort()).putShort(0)
            .putLong(captureTimestampNs).put(payload)
        return out
    }

    companion object {
        fun encodeRange(
            source: ByteArray,
            offset: Int,
            payloadLength: Int,
            flags: Int,
            sessionId: Long,
            frameId: Long,
            fragmentIndex: Int,
            fragmentCount: Int,
            captureTimestampNs: Long,
            videoKey: ByteArray? = null
        ): ByteArray {
            val encrypted = videoKey != null
            val maxPlain = if (encrypted) MAX_SEALED_VIDEO_PLAINTEXT_BYTES else MAX_VIDEO_PAYLOAD_BYTES
            require(
                flags in 0..255 &&
                    frameId in 0..0xffff_ffffL &&
                    fragmentCount in 1..65535 &&
                    fragmentIndex in 0 until fragmentCount &&
                    payloadLength in 1..maxPlain &&
                    offset >= 0 &&
                    offset + payloadLength <= source.size
            )
            val wireLen = if (encrypted) payloadLength + PacketSeal.TAG_BYTES else payloadLength
            val out = ByteArray(VIDEO_HEADER_BYTES + wireLen)
            val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
            b.put(VIDEO_MAGIC).put(1.toByte()).put(flags.toByte()).putLong(sessionId).putInt(frameId.toInt())
                .putShort(fragmentIndex.toShort()).putShort(fragmentCount.toShort()).putShort(wireLen.toShort()).putShort(0)
                .putLong(captureTimestampNs)
            if (encrypted) {
                val aad = out.copyOf(VIDEO_HEADER_BYTES)
                val sealed = PacketSeal.seal(
                    videoKey!!,
                    PacketSeal.videoNonce(frameId, fragmentIndex, fragmentCount),
                    aad,
                    source,
                    payloadLength,
                    offset
                )
                sealed.copyInto(out, VIDEO_HEADER_BYTES)
            } else {
                source.copyInto(out, VIDEO_HEADER_BYTES, offset, offset + payloadLength)
            }
            return out
        }

        fun decode(packet: ByteArray, length: Int = packet.size): VideoFragment {
            val h = parseHeader(packet, length)
            val payload = packet.copyOfRange(VIDEO_HEADER_BYTES, VIDEO_HEADER_BYTES + h.payloadLength)
            return VideoFragment(h.flags, h.sessionId, h.frameId, h.fragmentIndex, h.fragmentCount, payload, h.captureTimestampNs)
        }

        internal data class ParsedHeader(
            val flags: Int,
            val sessionId: Long,
            val frameId: Long,
            val fragmentIndex: Int,
            val fragmentCount: Int,
            val payloadLength: Int,
            val captureTimestampNs: Long
        )

        internal fun parseHeader(packet: ByteArray, packetLength: Int = packet.size): ParsedHeader {
            if (packetLength !in VIDEO_HEADER_BYTES..minOf(MAX_UDP_DATAGRAM_BYTES, packet.size)) {
                throw ProtocolException("Invalid UDP size")
            }
            val b = ByteBuffer.wrap(packet, 0, packetLength).order(ByteOrder.BIG_ENDIAN)
            if (b.get() != VIDEO_MAGIC[0] || b.get() != VIDEO_MAGIC[1]) throw ProtocolException("Invalid CZ magic")
            val version = b.get().toInt() and 255
            if (version != 1) throw ProtocolException("Unsupported video version")
            val flags = b.get().toInt() and 255
            val session = b.long
            val frame = b.int.toLong() and 0xffff_ffffL
            val index = b.short.toInt() and 65535
            val count = b.short.toInt() and 65535
            val length = b.short.toInt() and 65535
            b.short
            val timestamp = b.long
            if (count == 0 || index >= count || length != b.remaining()) {
                throw ProtocolException("Invalid fragment metadata")
            }
            return ParsedHeader(flags, session, frame, index, count, length, timestamp)
        }
    }
}

object VideoFragmenter {
    fun fragment(
        frame: ByteArray,
        sessionId: Long,
        frameId: Long,
        flags: Int,
        timestampNs: Long,
        length: Int = frame.size,
        videoKey: ByteArray? = null
    ): List<ByteArray> {
        require(length > 0 && length <= frame.size)
        val chunkMax = if (videoKey != null) MAX_SEALED_VIDEO_PLAINTEXT_BYTES else MAX_VIDEO_PAYLOAD_BYTES
        val count = (length + chunkMax - 1) / chunkMax
        require(count <= 4096)
        return List(count) { i ->
            val from = i * chunkMax
            val partLen = minOf(length - from, chunkMax)
            var f = flags
            if (i == 0) f = f or 1
            if (i == count - 1) f = f or 2
            VideoFragment.encodeRange(frame, from, partLen, f, sessionId, frameId, i, count, timestampNs, videoKey)
        }
    }
}

data class ReassembledFrame(val frameId: Long, val flags: Int, val bytes: ByteArray, val captureTimestampNs: Long)
data class ReassemblyOutcome(
    val frames: List<ReassembledFrame> = emptyList(),
    val requestPli: Boolean = false,
    val dropped: Int = 0,
    val rejected: Boolean = false
)

class VideoReassembler(
    private val sessionId: Long,
    private val frameDeadlineNs: Long = DEFAULT_FRAME_DEADLINE_NS,
    private val maxInFlight: Int = 6,
    private val pliCoalesceNs: Long = PLI_COALESCE_NS,
    private val maxFrameBytes: Int = 4 * 1024 * 1024,
    private val videoKey: ByteArray? = null
) {
    private data class Pending(
        val created: Long,
        val count: Int,
        val flags: Int,
        val timestamp: Long,
        val parts: Array<ByteArray?>,
        var total: Int = 0,
        var received: Int = 0,
        var lastNackNs: Long = Long.MIN_VALUE
    )

    private val pending = LinkedHashMap<Long, Pending>()
    private var newestCompleted: Long? = null
    private var lastPli = Long.MIN_VALUE

    @Synchronized
    fun accept(packet: ByteArray, nowNs: Long, length: Int = packet.size): ReassemblyOutcome {
        val (expiredCount, expiredPli) = expireInternal(nowNs)
        val h = try {
            VideoFragment.parseHeader(packet, length)
        } catch (_: Exception) {
            return ReassemblyOutcome(requestPli = expiredPli, dropped = expiredCount + 1, rejected = true)
        }
        if (h.sessionId != sessionId) {
            return ReassemblyOutcome(requestPli = expiredPli, dropped = expiredCount + 1, rejected = true)
        }
        if (newestCompleted?.let { h.frameId == it || older(h.frameId, it) } == true) {
            return ReassemblyOutcome(requestPli = expiredPli, dropped = expiredCount + 1)
        }
        val existing = pending[h.frameId]
        if (existing != null && h.fragmentIndex < existing.parts.size && existing.parts[h.fragmentIndex] != null) {
            return ReassemblyOutcome(requestPli = expiredPli, dropped = expiredCount)
        }
        val plaintextPart = if (videoKey != null) {
            try {
                val aad = packet.copyOf(VIDEO_HEADER_BYTES)
                PacketSeal.open(
                    videoKey,
                    PacketSeal.videoNonce(h.frameId, h.fragmentIndex, h.fragmentCount),
                    aad,
                    packet,
                    h.payloadLength,
                    VIDEO_HEADER_BYTES
                )
            } catch (_: Exception) {
                return ReassemblyOutcome(requestPli = expiredPli, dropped = expiredCount + 1, rejected = true)
            }
        } else {
            null
        }
        var dropped = expiredCount
        var needPli = expiredPli
        if (!pending.containsKey(h.frameId) && pending.size >= maxInFlight) {
            pending.remove(pending.keys.first())
            dropped++
            if (shouldPli(nowNs)) needPli = true
        }
        val p = pending.getOrPut(h.frameId) {
            Pending(nowNs, h.fragmentCount, h.flags, h.captureTimestampNs, arrayOfNulls(h.fragmentCount))
        }
        if (p.count != h.fragmentCount || p.timestamp != h.captureTimestampNs) {
            pending.remove(h.frameId)
            return ReassemblyOutcome(requestPli = shouldPli(nowNs) || needPli, dropped = dropped + 1, rejected = true)
        }
        if (p.parts[h.fragmentIndex] != null) {
            return ReassemblyOutcome(requestPli = needPli, dropped = dropped)
        }
        val partBytes = plaintextPart ?: packet.copyOfRange(VIDEO_HEADER_BYTES, VIDEO_HEADER_BYTES + h.payloadLength)
        if (p.total + partBytes.size > maxFrameBytes) {
            pending.remove(h.frameId)
            return ReassemblyOutcome(requestPli = shouldPli(nowNs) || needPli, dropped = dropped + 1, rejected = true)
        }
        p.parts[h.fragmentIndex] = partBytes
        p.total += partBytes.size
        p.received++
        if (p.received != p.count) {
            return ReassemblyOutcome(requestPli = needPli, dropped = dropped)
        }
        val result = ByteArray(p.total)
        var offset = 0
        p.parts.forEach { part ->
            val bytes = part ?: return ReassemblyOutcome(requestPli = needPli, dropped = dropped)
            bytes.copyInto(result, offset)
            offset += bytes.size
        }
        pending.remove(h.frameId)
        newestCompleted = h.frameId
        return ReassemblyOutcome(listOf(ReassembledFrame(h.frameId, p.flags, result, p.timestamp)), needPli, dropped)
    }

    /** Identifies incomplete multi-fragment frames missing <= maxMissing fragments past nackDelayNs. */
    @Synchronized
    fun collectNacks(
        nowNs: Long,
        nackDelayNs: Long = 8_000_000L,
        retryNs: Long = 10_000_000L,
        maxMissing: Int = 12
    ): List<ControlMessage.FragmentNack> {
        if (pending.isEmpty()) return emptyList()
        val nacks = ArrayList<ControlMessage.FragmentNack>(2)
        for ((frameId, p) in pending) {
            if (p.count <= 1 || p.received >= p.count) continue
            if (nowNs - p.created < nackDelayNs) continue
            if (p.lastNackNs != Long.MIN_VALUE && nowNs - p.lastNackNs < retryNs) continue
            val missingCount = p.count - p.received
            if (missingCount > maxMissing) continue
            val missing = ArrayList<Int>(missingCount)
            for (idx in 0 until p.count) {
                if (p.parts[idx] == null) missing.add(idx)
            }
            if (missing.isNotEmpty()) {
                p.lastNackNs = nowNs
                nacks.add(ControlMessage.FragmentNack(frameId, missing))
            }
        }
        return nacks
    }

    @Synchronized
    fun expire(nowNs: Long): Boolean = expireInternal(nowNs).second

    @Synchronized
    fun expireWithCount(nowNs: Long): Pair<Int, Boolean> = expireInternal(nowNs)

    private fun expireInternal(nowNs: Long): Pair<Int, Boolean> {
        if (pending.isEmpty()) return 0 to false
        var expiredCount = 0
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowNs - entry.value.created >= frameDeadlineNs) {
                it.remove()
                expiredCount++
            }
        }
        return if (expiredCount > 0) expiredCount to shouldPli(nowNs) else 0 to false
    }

    private fun shouldPli(now: Long): Boolean {
        if (lastPli != Long.MIN_VALUE && now - lastPli < pliCoalesceNs) return false
        lastPli = now
        return true
    }

    private fun older(a: Long, b: Long): Boolean = ((a - b) and 0xffff_ffffL) > 0x7fff_ffffL
}
