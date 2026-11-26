package com.castpro.zero.phone.capture

/** User-selectable local casting bitrates; 20 Mbps is the default. */
object BitrateOptions {
    val optionsMbps: List<Int> = listOf(10, 20, 30)
    const val DEFAULT_MBPS = 20

    fun toBps(mbps: Int): Int =
        (if (mbps in optionsMbps) mbps else DEFAULT_MBPS) * 1_000_000
}
