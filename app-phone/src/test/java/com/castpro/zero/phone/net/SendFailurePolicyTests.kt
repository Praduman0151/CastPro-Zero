package com.castpro.zero.phone.net

import com.castpro.zero.protocol.ControlMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketException

class SendFailurePolicyTests {
    @Test fun closedTransportReturnsNotSentInsteadOfPairingException() {
        val sender = PhoneSenderTransport("192.168.1.5", 45670, 45671, "token", onPli = {})
        val outcome = sender.sendVideoFrame(byteArrayOf(1, 2, 3), false, 123L)
        assertTrue(outcome is SendOutcome.NotSent)
        assertEquals(SendFailure.TRANSPORT_CLOSED, (outcome as SendOutcome.NotSent).failure)
    }

    @Test fun networkLossIsExpectedAndNeverBlamesPairing() {
        val failure = SendFailurePolicy.classify(
            SocketException("sendto failed: ENETUNREACH (Network is unreachable)")
        )
        assertEquals(SendFailure.NETWORK_UNAVAILABLE, failure)
        assertTrue(SendFailurePolicy.isExpected(failure))
        assertFalse(SendFailurePolicy.message(failure).contains("Pairing"))
    }

    @Test fun closedSocketIsNotReportedAsNetworkLoss() {
        assertEquals(
            SendFailure.TRANSPORT_CLOSED,
            SendFailurePolicy.classify(SocketException("Socket is closed"))
        )
    }

    @Test fun stalePairingStateIsAnExpectedShutdown() {
        val failure = SendFailurePolicy.classify(
            IllegalStateException("Pairing is required before UDP video")
        )
        assertEquals(SendFailure.TRANSPORT_CLOSED, failure)
        assertTrue(SendFailurePolicy.isExpected(failure))
    }

    @Test fun genericIoFailureIsNetworkLossButOtherErrorsStayVisible() {
        assertEquals(SendFailure.NETWORK_UNAVAILABLE, SendFailurePolicy.classify(IOException("Broken pipe")))
        val unexpected = SendFailurePolicy.classify(IllegalStateException("codec exploded"))
        assertEquals(SendFailure.UNEXPECTED, unexpected)
        assertFalse(SendFailurePolicy.isExpected(unexpected))
    }

    @Test fun alternateSocketClosedWordingIsAlsoTransportClosed() {
        assertEquals(
            SendFailure.TRANSPORT_CLOSED,
            SendFailurePolicy.classify(SocketException("Socket closed"))
        )
    }

    @Test fun unexpectedFailureRetainsItsCause() {
        val boom = IllegalStateException("codec exploded")
        val outcome = SendOutcome.NotSent(SendFailure.UNEXPECTED, boom)
        assertSame(boom, outcome.cause)
    }

    @Test fun closedTransportControlSendReturnsFalseWithoutPairingException() {
        val sender = PhoneSenderTransport("192.168.1.5", 45670, 45671, "token", onPli = {})
        assertFalse(sender.sendControl(ControlMessage.Ping(0L, 0)))
    }
}
