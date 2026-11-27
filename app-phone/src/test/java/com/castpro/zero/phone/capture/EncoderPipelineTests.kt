package com.castpro.zero.phone.capture

import com.castpro.zero.protocol.AnnexB
import com.castpro.zero.protocol.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class EncoderPipelineTests {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x38, 0x80.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte(), 0x22, 0x11)

    @Test fun combinedConfigAndKeyframeFlagExtractsBothCsdAndIdrFrame() {
        val combined = sps + pps + idr
        val result = EncoderOutputPolicy.classify(
            data = combined,
            length = combined.size,
            isConfig = true,
            isKeyframe = true,
            codec = VideoCodec.AVC
        )
        assertNotNull("Combined buffer must emit CSD", result.csdAnnexB)
        assertNotNull(AnnexB.extract(result.csdAnnexB!!, VideoCodec.AVC))
        assertTrue("Combined CODEC_CONFIG|KEY_FRAME must also emit IDR access unit", result.emitVideoFrame)
        assertTrue("Emitted video frame must be flagged as keyframe", result.keyframe)
        assertTrue(AnnexB.isIdr(result.videoBytes!!, VideoCodec.AVC))
    }

    @Test fun configOnlyBufferEmitsOnlyCsd() {
        val configOnly = sps + pps
        val result = EncoderOutputPolicy.classify(
            data = configOnly,
            length = configOnly.size,
            isConfig = true,
            isKeyframe = false,
            codec = VideoCodec.AVC
        )
        assertNotNull(result.csdAnnexB)
        assertFalse(result.emitVideoFrame)
    }

    @Test fun nonConfigVideoBufferPassesThroughWithoutCopyWhenRequested() {
        val result = EncoderOutputPolicy.classify(
            data = pFrame,
            length = pFrame.size,
            isConfig = false,
            isKeyframe = false,
            codec = VideoCodec.AVC
        )
        assertNull(result.csdAnnexB)
        assertTrue(result.emitVideoFrame)
        assertFalse(result.keyframe)
    }

    @Test fun frameBufferPoolReusesArraysAcrossLeaseAndRelease() {
        val pool = FrameBufferPool(maxPooled = 4)
        val first = pool.acquire(1024)
        assertTrue(first.size >= 1024)
        pool.release(first)
        val second = pool.acquire(512)
        assertSame("Pool must reuse existing array instead of allocating", first, second)
    }

    @Test fun boundedFrameQueueDropsBackloggedNonKeyframesAndRequiresSyncReset() {
        val pool = FrameBufferPool(maxPooled = 8)
        val q = BoundedFrameQueue(capacity = 2, pool = pool)
        fun enqueue(id: Byte, keyframe: Boolean): QueueEnqueueResult {
            val buf = pool.acquire(16)
            buf[0] = id
            return q.enqueue(buf, 16, keyframe, id * 1000L)
        }

        assertFalse(enqueue(1, keyframe = true).backlogClearedForSync)
        assertFalse(enqueue(2, keyframe = false).backlogClearedForSync)
        // Third frame overflows capacity=2: stale P-frames must be cleared and sync requested
        // so the TV decoder never sees an orphaned P-frame chain.
        val res = enqueue(3, keyframe = false)
        assertTrue(res.backlogClearedForSync)
        // Because frame 3 was a non-keyframe during backlog overflow, wait for next IDR instead of enqueueing broken P-frame.
        assertNull(q.poll(1))

        // Next IDR resets sync gate cleanly and delivers frame.
        assertFalse(enqueue(4, keyframe = true).backlogClearedForSync)
        val polled = q.poll(10)
        assertNotNull(polled)
        assertTrue(polled!!.keyframe)
        assertEquals(4.toByte(), polled.buffer[0])
        pool.release(polled.buffer)
    }
}
