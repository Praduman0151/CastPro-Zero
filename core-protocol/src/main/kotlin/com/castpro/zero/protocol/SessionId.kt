package com.castpro.zero.protocol

import java.security.SecureRandom

/** Creates the one random non-zero 64-bit identifier shared by TCP and UDP for a session. */
object SessionIds {
    private val secureRandom = SecureRandom()

    @Synchronized
    fun newSessionId(): Long {
        while (true) {
            val value = secureRandom.nextLong()
            if (value != 0L) return value
        }
    }
}
