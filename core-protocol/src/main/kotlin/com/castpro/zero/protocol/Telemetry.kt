package com.castpro.zero.protocol

/** Snapshot published by the phone sender for its live stats panel. */
data class SenderStats(
    val framesSent: Long = 0,
    val bytesSent: Long = 0,
    val pliCount: Long = 0,
    val rttMs: Long = -1,
    val fps: Int = 0,
    val mbps: Float = 0f,
    val codec: String = "idle"
)
