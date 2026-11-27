package com.castpro.zero.phone.capture

import com.castpro.zero.protocol.AnnexB
import com.castpro.zero.protocol.VideoCodec
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class OutputClassification(
    val csdAnnexB: ByteArray? = null,
    val emitVideoFrame: Boolean = false,
    val keyframe: Boolean = false,
    val videoBytes: ByteArray? = null,
    val videoLength: Int = 0
)

/**
 * Separates MediaCodec CSD handling from access unit transmission.
 * Handles combined CODEC_CONFIG | KEY_FRAME output buffers without dropping the IDR slice.
 */
object EncoderOutputPolicy {
    fun classify(
        data: ByteArray,
        length: Int,
        isConfig: Boolean,
        isKeyframe: Boolean,
        codec: VideoCodec = VideoCodec.AVC
    ): OutputClassification {
        require(length in 1..data.size)
        if (!isConfig) {
            return OutputClassification(
                csdAnnexB = null,
                emitVideoFrame = true,
                keyframe = isKeyframe,
                videoBytes = data,
                videoLength = length
            )
        }
        val slice = if (length == data.size) data else data.copyOf(length)
        val sets = AnnexB.extract(slice, codec)
        val csdBytes = sets?.annexB()
        val containsIdr = isKeyframe || AnnexB.isIdr(slice, codec)
        return OutputClassification(
            csdAnnexB = csdBytes,
            emitVideoFrame = containsIdr,
            keyframe = containsIdr,
            videoBytes = if (containsIdr) data else null,
            videoLength = if (containsIdr) length else 0
        )
    }
}

class PooledEncodedFrame(
    val buffer: ByteArray,
    val length: Int,
    val keyframe: Boolean,
    val captureTimestampNs: Long
)

/** Small lock-guarded ByteArray pool eliminating per-frame heap allocations on encoder output. */
class FrameBufferPool(private val maxPooled: Int = 12) {
    private val buffers = ArrayDeque<ByteArray>()

    @Synchronized
    fun acquire(minCapacity: Int): ByteArray {
        val iterator = buffers.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size >= minCapacity) {
                iterator.remove()
                return candidate
            }
        }
        if (buffers.isNotEmpty() && buffers.size >= maxPooled) {
            buffers.removeFirst()
        }
        return ByteArray(maxOf(minCapacity, 4096))
    }

    @Synchronized
    fun release(buffer: ByteArray) {
        if (buffers.size >= maxPooled) {
            // Evict smallest buffer if incoming is larger so pool adapts to bitrate spikes.
            val smallestIdx = buffers.indices.minByOrNull { buffers[it].size } ?: return
            if (buffers[smallestIdx].size >= buffer.size) return
            buffers.removeAt(smallestIdx)
        }
        buffers.addLast(buffer)
    }

    @Synchronized
    fun clear() {
        buffers.clear()
    }
}

data class QueueEnqueueResult(
    val enqueued: Boolean,
    val backlogClearedForSync: Boolean
)

/**
 * Decouples MediaCodec.releaseOutputBuffer from blocking UDP transmission.
 * When congestion causes queue overflow, queued stale non-key frames are flushed and an IDR
 * sync-frame is requested so the TV decoder never receives a broken P-frame dependency chain.
 */
class BoundedFrameQueue(
    private val capacity: Int = 2,
    private val pool: FrameBufferPool
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<PooledEncodedFrame>(capacity)
    private var closed = false
    private var awaitingSyncAfterDrop = false

    fun enqueue(buffer: ByteArray, length: Int, keyframe: Boolean, captureTimestampNs: Long): QueueEnqueueResult {
        lock.withLock {
            if (closed) {
                pool.release(buffer)
                return QueueEnqueueResult(enqueued = false, backlogClearedForSync = false)
            }
            if (awaitingSyncAfterDrop && !keyframe) {
                pool.release(buffer)
                return QueueEnqueueResult(enqueued = false, backlogClearedForSync = false)
            }
            if (keyframe) {
                awaitingSyncAfterDrop = false
            }
            if (queue.size >= capacity) {
                while (queue.isNotEmpty()) {
                    pool.release(queue.removeFirst().buffer)
                }
                if (keyframe) {
                    queue.addLast(PooledEncodedFrame(buffer, length, true, captureTimestampNs))
                    notEmpty.signal()
                    return QueueEnqueueResult(enqueued = true, backlogClearedForSync = false)
                } else {
                    pool.release(buffer)
                    awaitingSyncAfterDrop = true
                    return QueueEnqueueResult(enqueued = false, backlogClearedForSync = true)
                }
            }
            queue.addLast(PooledEncodedFrame(buffer, length, keyframe, captureTimestampNs))
            notEmpty.signal()
            return QueueEnqueueResult(enqueued = true, backlogClearedForSync = false)
        }
    }

    fun poll(timeoutMs: Long): PooledEncodedFrame? {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        lock.withLock {
            while (queue.isEmpty() && !closed) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return null
                notEmpty.awaitNanos(remainingNs)
            }
            return if (queue.isEmpty()) null else queue.removeFirst()
        }
    }

    fun clear() {
        lock.withLock {
            while (queue.isNotEmpty()) {
                pool.release(queue.removeFirst().buffer)
            }
            awaitingSyncAfterDrop = false
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            while (queue.isNotEmpty()) {
                pool.release(queue.removeFirst().buffer)
            }
            notEmpty.signalAll()
        }
    }
}
