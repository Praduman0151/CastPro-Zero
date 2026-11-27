package com.castpro.zero.phone.net

import java.io.IOException
import java.net.SocketException

/** Why a frame did not reach the TV; all values are expected cast-end conditions except UNEXPECTED. */
enum class SendFailure { TRANSPORT_CLOSED, NETWORK_UNAVAILABLE, UNEXPECTED }

sealed class SendOutcome {
    data class Sent(val frameId: Long, val fragmentCount: Int, val byteCount: Int) : SendOutcome()
    data class NotSent(val failure: SendFailure, val cause: Throwable? = null) : SendOutcome()
}

object SendFailurePolicy {
    /** Closed sockets and lost networks are routine teardown, never a pairing error. */
    fun classify(error: Throwable): SendFailure = when {
        error.message?.contains("Pairing is required", ignoreCase = true) == true -> SendFailure.TRANSPORT_CLOSED
        error.message?.let {
            it.contains("Socket is closed", ignoreCase = true) || it.contains("Socket closed", ignoreCase = true)
        } == true -> SendFailure.TRANSPORT_CLOSED
        error is SocketException || error is IOException -> SendFailure.NETWORK_UNAVAILABLE
        else -> SendFailure.UNEXPECTED
    }

    fun message(failure: SendFailure): String = when (failure) {
        SendFailure.TRANSPORT_CLOSED -> "Casting stopped"
        SendFailure.NETWORK_UNAVAILABLE -> "Network unavailable; casting stopped"
        SendFailure.UNEXPECTED -> "Casting failed"
    }

    fun isExpected(failure: SendFailure): Boolean = failure != SendFailure.UNEXPECTED
}
