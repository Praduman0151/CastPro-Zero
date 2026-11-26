package com.castpro.zero.tv

import kotlin.math.min

data class FittedSize(val width: Int, val height: Int)

object SurfaceLayoutPolicy {
    /** Largest centered rectangle that preserves the source aspect ratio. */
    fun fit(containerWidth: Int, containerHeight: Int, videoWidth: Int, videoHeight: Int): FittedSize {
        require(containerWidth > 0 && containerHeight > 0 && videoWidth > 0 && videoHeight > 0)
        val scale = min(containerWidth.toDouble() / videoWidth, containerHeight.toDouble() / videoHeight)
        return FittedSize((videoWidth * scale).toInt().coerceAtLeast(1),
            (videoHeight * scale).toInt().coerceAtLeast(1))
    }
}

class SurfaceRecoveryState {
    var cachedCsd: ByteArray? = null
        private set
    var surfaceAvailable: Boolean = false
        private set
    var decoderReady: Boolean = false
        private set
    var pliRequests: Int = 0
        private set

    fun onCsd(bytes: ByteArray) { cachedCsd = bytes.copyOf() }
    fun onSurfaceAvailable() {
        surfaceAvailable = true
        if (cachedCsd != null) { decoderReady = true; pliRequests++ }
    }
    fun onSurfaceDestroyed() { surfaceAvailable = false; decoderReady = false }
    fun onStopOrDisconnect() {
        surfaceAvailable = false; decoderReady = false; cachedCsd = null
    }
}
