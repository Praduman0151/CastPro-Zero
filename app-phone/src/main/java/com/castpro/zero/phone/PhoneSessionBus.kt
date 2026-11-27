package com.castpro.zero.phone

import com.castpro.zero.protocol.SenderStats

/** In-process bridge from the foreground capture service to Compose UI. */
object PhoneSessionBus {
    @Volatile var onStats: ((SenderStats) -> Unit)? = null
    @Volatile var onStreamingChanged: ((Boolean) -> Unit)? = null
    @Volatile var onAudioChanged: ((Boolean) -> Unit)? = null
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun publish(stats: SenderStats) { onStats?.invoke(stats) }
    fun publishStreaming(active: Boolean) { onStreamingChanged?.invoke(active) }
    fun publishAudio(active: Boolean) { onAudioChanged?.invoke(active) }
    fun publishStatus(value: String) { onStatus?.invoke(value) }
}
