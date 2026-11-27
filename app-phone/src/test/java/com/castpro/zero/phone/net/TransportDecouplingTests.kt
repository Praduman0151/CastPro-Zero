package com.castpro.zero.phone.net

import com.castpro.zero.protocol.AudioFrame
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException

class TransportDecouplingTests {
    @Test fun sentFragmentCacheRetainsRecentFramesAndEvictsOldest() {
        val cache = SentFragmentCache(maxFrames = 2)
        val f1 = arrayOf(byteArrayOf(1), byteArrayOf(2))
        val f2 = arrayOf(byteArrayOf(3), byteArrayOf(4))
        val f3 = arrayOf(byteArrayOf(5), byteArrayOf(6))
        cache.put(10L, f1)
        cache.put(11L, f2)
        assertSame(f1[1], cache.get(10L, 1))
        cache.put(12L, f3)
        assertNull(cache.get(10L, 0))
        assertSame(f2[0], cache.get(11L, 0))
        assertSame(f3[1], cache.get(12L, 1))
    }

    @Test fun transientBufferOverflowIsClassifiedAsNonFatalCongestion() {
        val enobufs = SendFailurePolicy.classify(SocketException("sendto failed: ENOBUFS (No buffer space available)"))
        val eagain = SendFailurePolicy.classify(SocketException("EAGAIN (Try again)"))
        assertEquals(SendFailure.TRANSIENT_CONGESTION, enobufs)
        assertEquals(SendFailure.TRANSIENT_CONGESTION, eagain)
        assertFalse(SendFailurePolicy.isFatal(enobufs))
        assertTrue(SendFailurePolicy.isFatal(SendFailure.TRANSPORT_CLOSED))
        assertTrue(SendFailurePolicy.isFatal(SendFailure.NETWORK_UNAVAILABLE))
    }

    @Test fun closedTransportAudioSendReturnsNotSentInsteadOfThrowing() {
        val sender = PhoneSenderTransport("192.168.1.5", 45670, 45671, "token_1234567890", onPli = {})
        val outcome = sender.sendAudioFrame(AudioFrame(0L, 1L, 100L, byteArrayOf(1, 2, 3, 4)))
        assertTrue(outcome is SendOutcome.NotSent)
        assertEquals(SendFailure.TRANSPORT_CLOSED, (outcome as SendOutcome.NotSent).failure)
    }
}
