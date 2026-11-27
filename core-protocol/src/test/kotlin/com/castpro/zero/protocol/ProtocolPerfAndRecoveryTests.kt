package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProtocolPerfAndRecoveryTests {
    @Test fun annexBPreservesFourByteEndNalAndTrailingZeroInsideRbsp() {
        val nal1 = byteArrayOf(0x65, 0x11, 0x22)
        val nal2TrailingZero = byteArrayOf(0x41, 0x00)
        val stream = byteArrayOf(0, 0, 0, 1) + nal1 + byteArrayOf(0, 0, 1) + nal2TrailingZero
        val nals = AnnexB.nalUnits(stream)
        assertEquals(2, nals.size)
        assertArrayEquals(nal1, nals[0])
        assertArrayEquals(nal2TrailingZero, nals[1])
        assertTrue(AnnexB.isIdr(stream, VideoCodec.AVC))
    }

    @Test fun videoReassemblerMaxInFlightEvictionRequestsPliAndTracksDrop() {
        val r = VideoReassembler(sessionId = 77L, maxInFlight = 2)
        val f1 = VideoFragmenter.fragment(ByteArray(2800) { 1 }, 77L, 1L, 0, 100L)
        val f2 = VideoFragmenter.fragment(ByteArray(2800) { 2 }, 77L, 2L, 0, 200L)
        val f3 = VideoFragmenter.fragment(ByteArray(2800) { 3 }, 77L, 3L, 0, 300L)
        assertFalse(r.accept(f1[0], 1_000_000L).requestPli)
        assertFalse(r.accept(f2[0], 1_000_100L).requestPli)
        // Third incomplete frame evicts frame 1: must count drop AND request recovery PLI.
        val evictionOutcome = r.accept(f3[0], 1_000_200L)
        assertTrue(evictionOutcome.dropped >= 1)
        assertTrue(evictionOutcome.requestPli)
    }

    @Test fun duplicateFragmentForNewestCompletedFrameIsIgnoredCleanly() {
        val r = VideoReassembler(sessionId = 77L, frameDeadlineNs = 20_000_000L)
        val f10 = VideoFragmenter.fragment(ByteArray(2800) { 5 }, 77L, 10L, 4, 1_000L)
        assertTrue(r.accept(f10[0], 1_000_000L).frames.isEmpty())
        assertTrue(r.accept(f10[1], 1_000_100L).frames.isEmpty())
        val completed = r.accept(f10[2], 1_000_200L)
        assertEquals(1, completed.frames.size)
        // Late duplicate of frame 10 fragment 0 must not enter pending or later trigger PLI.
        val dup = r.accept(f10[0], 1_000_300L)
        assertTrue(dup.frames.isEmpty())
        assertFalse(r.expire(50_000_000L))
    }

    @Test fun nackIdentifiesMissingFragmentsBeforeExpiryWithoutSpam() {
        val r = VideoReassembler(sessionId = 77L, frameDeadlineNs = 45_000_000L)
        val frags = VideoFragmenter.fragment(ByteArray(4500) { 7 }, 77L, 42L, 0, 100L)
        assertEquals(4, frags.size)
        r.accept(frags[0], 1_000_000L)
        r.accept(frags[2], 1_100_000L)
        r.accept(frags[3], 1_200_000L)
        // Too early (2ms): no NACK yet so normal jitter resolves naturally.
        assertTrue(r.collectNacks(3_000_000L, nackDelayNs = 8_000_000L).isEmpty())
        // At 10ms: missing fragment #1 is surfaced once.
        val nacks = r.collectNacks(10_000_000L, nackDelayNs = 8_000_000L)
        assertEquals(1, nacks.size)
        assertEquals(42L, nacks[0].frameId)
        assertEquals(listOf(1), nacks[0].missingIndices)
        // Calling immediately again coalesces instead of flooding NACKs.
        assertTrue(r.collectNacks(11_000_000L, nackDelayNs = 8_000_000L).isEmpty())
    }

    @Test fun fragmentNackRoundTripsOverControlFramingInSingleWrite() {
        val msg = ControlMessage.FragmentNack(frameId = 987654321L, missingIndices = listOf(1, 3, 12))
        val encoded = ControlMessageCodec.encode(msg)
        val decoded = ControlMessageCodec.decode(ControlType.FRAGMENT_NACK, encoded)
        assertEquals(msg, decoded)

        var writeCalls = 0
        val out = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                writeCalls++
                super.write(b, off, len)
            }
        }
        ControlFraming.write(out, ControlFrame(ControlType.FRAGMENT_NACK, sequence = 5, sessionId = 123L, payload = encoded))
        assertEquals("ControlFraming.write must issue one write syscall, not split header and payload", 1, writeCalls)
    }

    @Test fun smoothPacingPacesMediumAndLargeIdrFrames() {
        val small = UdpBurstPacer.smoothPlan(4)
        assertEquals(0L, small.pauseMicros)
        val med = UdpBurstPacer.smoothPlan(30)
        assertTrue(med.pauseMicros > 0L && med.burstSize <= 8)
        val large = UdpBurstPacer.smoothPlan(120)
        assertTrue(large.pauseMicros > 0L && large.burstSize <= 10)
    }
}
