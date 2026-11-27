package com.castpro.zero.phone.capture

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/** Schedules one-shot capture startup work off the calling thread, with cancellation. */
class OffThreadStartup(private val executor: ExecutorService) {
    fun execute(block: () -> Unit) {
        try { executor.execute { block() } } catch (_: RejectedExecutionException) {}
    }

    /** Prevents queued startup from resurrecting a session that was stopped meanwhile. */
    fun cancel() { executor.shutdownNow() }
}
