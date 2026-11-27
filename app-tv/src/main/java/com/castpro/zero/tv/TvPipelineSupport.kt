package com.castpro.zero.tv

import com.castpro.zero.protocol.ControlMessage
import com.castpro.zero.protocol.ReassembledFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong

/**
 * Measures real hardware presentation FPS from OnFrameRenderedListener callbacks,
 * dividing strictly by exact elapsed nanoseconds so sleep/GC jitter cannot inflate FPS >60.
 */
class VsyncFpsTracker {
    private val count = AtomicLong(0L)
    @Volatile private var lastCount = 0L

    fun onFrameRendered(@Suppress("UNUSED_PARAMETER") renderNanoTime: Long) {
        count.incrementAndGet()
    }

    fun totalRendered(): Long = count.get()

    @Synchronized
    fun sampleFps(windowStartNs: Long, windowEndNs: Long): Long {
        val current = count.get()
        val delta = (current - lastCount).coerceAtLeast(0L)
        lastCount = current
        val elapsedNs = (windowEndNs - windowStartNs).coerceAtLeast(1L)
        return (delta * 1_000_000_000.0 / elapsedNs).roundToLong()
    }

    @Synchronized
    fun reset() {
        count.set(0L)
        lastCount = 0L
    }
}

data class FrameQueueOfferResult(
    val accepted: Boolean,
    val overflowed: Boolean,
    val discontinuityRequired: Boolean
)

/**
 * Bounded frame handoff between UDP socket thread (CPZ-TV-udp) and MediaCodec feeder
 * (CPZ-TV-decoder-feed). Ensures UDP socket receives never block on dequeueInputBuffer,
 * while preventing orphaned P-frames after overflow or packet loss.
 */
class TvVideoFrameQueue(private val capacity: Int = 6) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<ReassembledFrame>(capacity)
    private var discontinuityPending = false
    private var closed = false

    fun offer(frame: ReassembledFrame): FrameQueueOfferResult {
        val isKeyframe = (frame.flags and 4) != 0
        lock.withLock {
            if (closed) return FrameQueueOfferResult(accepted = false, overflowed = false, discontinuityRequired = false)
            if (discontinuityPending) {
                if (!isKeyframe) {
                    return FrameQueueOfferResult(accepted = false, overflowed = false, discontinuityRequired = false)
                }
                discontinuityPending = false
            }
            if (queue.size >= capacity) {
                queue.clear()
                return if (isKeyframe) {
                    queue.addLast(frame)
                    notEmpty.signal()
                    FrameQueueOfferResult(accepted = true, overflowed = true, discontinuityRequired = false)
                } else {
                    discontinuityPending = true
                    FrameQueueOfferResult(accepted = false, overflowed = true, discontinuityRequired = true)
                }
            }
            queue.addLast(frame)
            notEmpty.signal()
            return FrameQueueOfferResult(accepted = true, overflowed = false, discontinuityRequired = false)
        }
    }

    fun poll(timeoutMs: Long): ReassembledFrame? {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        lock.withLock {
            while (queue.isEmpty() && !closed) {
                val rem = deadlineNs - System.nanoTime()
                if (rem <= 0L) return null
                notEmpty.awaitNanos(rem)
            }
            return if (queue.isEmpty()) null else queue.removeFirst()
        }
    }

    fun markDiscontinuity() {
        lock.withLock {
            queue.clear()
            discontinuityPending = true
        }
    }

    fun signalWakeup() {
        lock.withLock { notEmpty.signalAll() }
    }

    fun clear() {
        lock.withLock {
            queue.clear()
            discontinuityPending = false
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            queue.clear()
            notEmpty.signalAll()
        }
    }
}

/** Non-blocking control message outbound queue avoiding lock contention on UI and UDP threads. */
class TvControlSendQueue(capacity: Int = 64) {
    private val queue = ArrayBlockingQueue<ControlMessage>(capacity)

    fun offer(message: ControlMessage): Boolean = queue.offer(message)

    fun poll(timeoutMs: Long): ControlMessage? =
        queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    fun clear() = queue.clear()
}
