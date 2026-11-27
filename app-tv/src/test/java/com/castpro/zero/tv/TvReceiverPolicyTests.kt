package com.castpro.zero.tv

import com.castpro.zero.protocol.StreamHealthCounters
import org.junit.Assert.*
import org.junit.Test

class TvReceiverPolicyTests {
    @Test fun decoderBackpressureNeverRequiresPli() {
        assertFalse(TvReceiverPolicy.pliRequiredForBackpressure())
    }

    @Test fun audioPortIsDerivedFromVideoPort() {
        assertEquals(45673, TvReceiverPolicy.audioPortFor(45671))
        assertEquals(45701, TvReceiverPolicy.audioPortFor(45699))
    }

    @Test fun healthFormatSeparatesLossFromBackpressure() {
        val c = StreamHealthCounters()
        repeat(5) { c.onDecoderBackpressure() }
        c.onReassemblyDrop()
        assertEquals("loss=1 busy=5 setup=0", TvReceiverPolicy.formatHealth(c.snapshot()))
    }

    @Test fun sequenceAdvancesOnlyOnACompleteAudioWrite() {
        assertTrue(TvReceiverPolicy.shouldAdvanceSequence(1328, 1328))
        assertFalse(TvReceiverPolicy.shouldAdvanceSequence(0, 1328))
        assertFalse(TvReceiverPolicy.shouldAdvanceSequence(-2, 1328))
        assertFalse(TvReceiverPolicy.shouldAdvanceSequence(512, 1328))
    }

    @Test fun audioFailuresNeverFatalToVideo() {
        assertTrue(TvReceiverPolicy.audioFailureIsNonFatal())
    }

    @Test fun unsupportedAudioConfigIsRejected() {
        assertTrue(TvReceiverPolicy.isSupportedAudioConfig(48_000, 2, 16, 1))
        assertFalse(TvReceiverPolicy.isSupportedAudioConfig(44_100, 2, 16, 1))
        assertFalse(TvReceiverPolicy.isSupportedAudioConfig(48_000, 1, 16, 1))
        assertFalse(TvReceiverPolicy.isSupportedAudioConfig(48_000, 2, 8, 1))
        assertFalse(TvReceiverPolicy.isSupportedAudioConfig(48_000, 2, 16, 3))
    }
}
