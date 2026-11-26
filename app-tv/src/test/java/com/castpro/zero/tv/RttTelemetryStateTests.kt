package com.castpro.zero.tv

import com.castpro.zero.protocol.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class RttTelemetryStateTests {
    @Test fun matchingPongUpdatesLiveRtt() {
        val state = RttTelemetryState()
        state.recordPing(1_000_000L, 7L)
        assertTrue(state.acceptPong(ControlMessage.Pong(1_000_000L, 7L), 8_500_000L))
        assertEquals(7L, state.rttMs)
    }

    @Test fun staleOrMismatchedPongCannotChangeRtt() {
        val state = RttTelemetryState()
        state.recordPing(1_000_000L, 7L)
        assertFalse(state.acceptPong(ControlMessage.Pong(1_000_000L, 6L), 8_500_000L))
        assertEquals(-1L, state.rttMs)
    }
}
