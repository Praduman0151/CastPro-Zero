package com.castpro.zero.tv

import com.castpro.zero.protocol.DecodeGate
import com.castpro.zero.protocol.VideoCodec

/**
 * Pure session state shared by the TV receiver and its unit tests: caches the
 * authenticated CSD, gates frames until an IDR, and remembers what must be
 * replayed after a SurfaceView recreation or a PLI.
 */
class DecoderSessionState(private val codec: VideoCodec = VideoCodec.AVC) {
    private var csd: ByteArray? = null
    private var gate = DecodeGate(codec)

    var pliCount: Long = 0
        private set
    var csdReplays: Long = 0
        private set

    fun onCsd(data: ByteArray): Boolean {
        if (!gate.onCsd(data)) return false
        csd = data.copyOf()
        return true
    }

    fun csdForDecoder(): ByteArray? = csd?.copyOf()

    fun allowFrame(bytes: ByteArray): Boolean = gate.allowFrame(bytes)

    /** Surface recreation keeps the session CSD and asks the sender for a fresh IDR. */
    fun onSurfaceRecreated(): ByteArray? {
        gate.resetForDiscontinuity()
        csdReplays++
        pliCount++
        return csdForDecoder()
    }

    /** PLI replays the cached CSD before the sender's new sync frame. */
    fun onPli(): ByteArray? {
        pliCount++
        return csdForDecoder()
    }

    fun resetForDiscontinuity() = gate.resetForDiscontinuity()

    fun resetSession() {
        csd = null
        gate = DecodeGate(codec)
    }
}
