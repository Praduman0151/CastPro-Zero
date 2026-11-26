package com.castpro.zero.protocol

import java.security.SecureRandom

/** Creates the one random 64-bit identifier shared by TCP and UDP for a session. */
object SessionIds {
    private val secureRandom = SecureRandom()

    @Synchronized
    fun newSessionId(): Long = secureRandom.nextLong()
}
