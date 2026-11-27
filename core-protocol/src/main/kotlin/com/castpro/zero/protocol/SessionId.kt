package com.castpro.zero.protocol

import java.security.SecureRandom

/** Creates the one random non-zero 63-bit positive identifier shared by TCP and UDP for a session. */
object SessionIds {
    private val secureRandom = SecureRandom()

    @Synchronized
    fun newSessionId(): Long {
        while (true) {
            val value = secureRandom.nextLong() ushr 1
            if (value != 0L) return value
        }
    }
}
