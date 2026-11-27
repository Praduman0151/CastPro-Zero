package com.castpro.zero.protocol

enum class VideoCodec { AVC, HEVC }

data class CodecParameterSets(val codec: VideoCodec, val units: Map<Int, ByteArray>) {
    fun annexB(): ByteArray {
        val sorted = units.toSortedMap().values
        val total = sorted.sumOf { 4 + it.size }
        val out = ByteArray(total)
        var pos = 0
        for (nal in sorted) {
            out[pos] = 0; out[pos + 1] = 0; out[pos + 2] = 0; out[pos + 3] = 1
            pos += 4
            nal.copyInto(out, pos)
            pos += nal.size
        }
        return out
    }
}

object AnnexB {
    /**
     * Zero-allocation scanner over Annex-B NAL units in `data[0 until length]`.
     * Checks 4-byte start codes (00 00 00 01) BEFORE 3-byte start codes (00 00 01) so
     * legitimate trailing 0x00 bytes inside RBSP payloads are never misattributed or stripped.
     */
    inline fun forEachNalUnit(
        data: ByteArray,
        length: Int = data.size,
        visitor: (offset: Int, nalLength: Int) -> Unit
    ) {
        val limit = minOf(length, data.size)
        var i = 0
        var nalStart = -1
        while (i + 2 < limit) {
            val isFour = i + 3 < limit &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isThree = !isFour &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (isFour || isThree) {
                if (nalStart >= 0 && i > nalStart) {
                    visitor(nalStart, i - nalStart)
                }
                i += if (isFour) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until limit) {
            visitor(nalStart, limit - nalStart)
        }
    }

    fun nalUnits(data: ByteArray, length: Int = data.size): List<ByteArray> {
        val result = ArrayList<ByteArray>(4)
        forEachNalUnit(data, length) { offset, nalLen ->
            result.add(data.copyOfRange(offset, offset + nalLen))
        }
        return result
    }

    fun nalTypes(data: ByteArray, codec: VideoCodec, length: Int = data.size): List<Int> {
        val types = ArrayList<Int>(4)
        forEachNalUnit(data, length) { offset, _ ->
            val header = data[offset].toInt()
            types.add(if (codec == VideoCodec.AVC) header and 31 else (header ushr 1) and 63)
        }
        return types
    }

    private fun nalType(headerByte: Byte, codec: VideoCodec): Int {
        val v = headerByte.toInt()
        return if (codec == VideoCodec.AVC) v and 31 else (v ushr 1) and 63
    }

    private fun trimTrailingZeroPadding(nal: ByteArray): ByteArray {
        var end = nal.size
        while (end > 1 && nal[end - 1] == 0.toByte()) end--
        return if (end == nal.size) nal else nal.copyOf(end)
    }

    fun extract(data: ByteArray, codec: VideoCodec, length: Int = data.size): CodecParameterSets? {
        val required = if (codec == VideoCodec.AVC) intArrayOf(7, 8) else intArrayOf(32, 33, 34)
        val sets = LinkedHashMap<Int, ByteArray>(required.size)
        forEachNalUnit(data, length) { offset, nalLen ->
            if (nalLen >= 2) {
                val t = nalType(data[offset], codec)
                if (t in required && !sets.containsKey(t)) {
                    sets[t] = trimTrailingZeroPadding(data.copyOfRange(offset, offset + nalLen))
                }
            }
        }
        return if (required.all { sets.containsKey(it) }) CodecParameterSets(codec, sets) else null
    }

    fun isIdr(data: ByteArray, codec: VideoCodec, length: Int = data.size): Boolean {
        var found = false
        forEachNalUnit(data, length) { offset, nalLen ->
            if (!found && nalLen >= 1) {
                val t = nalType(data[offset], codec)
                if ((codec == VideoCodec.AVC && t == 5) ||
                    (codec == VideoCodec.HEVC && t in 19..21)
                ) {
                    found = true
                }
            }
        }
        return found
    }
}

class DecodeGate(private val codec: VideoCodec) {
    enum class State { AWAITING_CSD, AWAITING_IDR, OPEN }
    // Control/attach threads mutate the gate; the UDP/decoder threads read it per frame.
    @Volatile var state = State.AWAITING_CSD; private set
    @Volatile var parameterSets: CodecParameterSets? = null; private set

    @Synchronized fun onCsd(data: ByteArray): Boolean {
        val sets = AnnexB.extract(data, codec) ?: return false
        parameterSets = sets
        state = State.AWAITING_IDR
        return true
    }

    @Synchronized fun allowFrame(data: ByteArray, length: Int = data.size): Boolean {
        if (state == State.AWAITING_CSD) return false
        if (state == State.AWAITING_IDR) {
            if (!AnnexB.isIdr(data, codec, length)) return false
            state = State.OPEN
        }
        return state == State.OPEN
    }

    @Synchronized fun resetForDiscontinuity() {
        state = if (parameterSets == null) State.AWAITING_CSD else State.AWAITING_IDR
    }

    /** A recreated decoder has no parameter sets, so no frame may reach it until CSD is queued again. */
    @Synchronized fun onDecoderRecreated() {
        parameterSets = null
        state = State.AWAITING_CSD
    }
}
