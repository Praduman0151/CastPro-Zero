package com.castpro.zero.phone.net

import java.io.IOException
import java.net.SocketException

/** Why a frame did not reach the TV; TRANSIENT_CONGESTION is recoverable without ending MediaProjection. */
enum class SendFailure { TRANSPORT_CLOSED, NETWORK_UNAVAILABLE, TRANSIENT_CONGESTION, UNEXPECTED }

sealed class SendOutcome {
    data class Sent(val frameId: Long, val fragmentCount: Int, val byteCount: Int) : SendOutcome()
    data class NotSent(val failure: SendFailure, val cause: Throwable? = null) : SendOutcome()
}

object SendFailurePolicy {
    /** Closed sockets, buffer backpressure and lost networks are handled gracefully, never blaming pairing. */
    fun classify(error: Throwable): SendFailure {
        val msg = error.message
        if (msg?.contains("Pairing is required", ignoreCase = true) == true) return SendFailure.TRANSPORT_CLOSED
        if (msg != null && (msg.contains("Socket is closed", ignoreCase = true) ||
                msg.contains("Socket closed", ignoreCase = true))
        ) {
            return SendFailure.TRANSPORT_CLOSED
        }
        if (msg != null && (msg.contains("ENOBUFS", ignoreCase = true) ||
                msg.contains("No buffer space", ignoreCase = true) ||
                msg.contains("EAGAIN", ignoreCase = true) ||
                msg.contains("Try again", ignoreCase = true) ||
                msg.contains("Resource temporarily unavailable", ignoreCase = true))
        ) {
            return SendFailure.TRANSIENT_CONGESTION
        }
        return when {
            error is SocketException || error is IOException -> SendFailure.NETWORK_UNAVAILABLE
            else -> SendFailure.UNEXPECTED
        }
    }

    fun message(failure: SendFailure): String = when (failure) {
        SendFailure.TRANSPORT_CLOSED -> "Casting stopped"
        SendFailure.NETWORK_UNAVAILABLE -> "Network unavailable; casting stopped"
        SendFailure.TRANSIENT_CONGESTION -> "Network buffer busy; dropping frame"
        SendFailure.UNEXPECTED -> "Casting failed"
    }

    fun isExpected(failure: SendFailure): Boolean = failure != SendFailure.UNEXPECTED

    fun isFatal(failure: SendFailure): Boolean = failure != SendFailure.TRANSIENT_CONGESTION
}
