package com.castpro.zero.phone.capture

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OffThreadStartupTests {
    @Test
    fun `startup work never runs inline on the calling thread`() {
        val caller = Thread.currentThread()
        val ranOn = AtomicReference<Thread>()
        val finished = CountDownLatch(1)
        val runner = OffThreadStartup(Executors.newSingleThreadExecutor { command -> Thread(command, "CPZ-test-startup") })

        runner.execute {
            ranOn.set(Thread.currentThread())
            finished.countDown()
        }

        assertTrue("startup work must be scheduled off the caller", finished.await(2, TimeUnit.SECONDS))
        assertNotSame("startup work ran inline on the caller", caller, ranOn.get())
    }

    @Test
    fun `cancel prevents queued startup from resurrecting a stopped session`() {
        val service: ExecutorService = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        val runner = OffThreadStartup(service)
        runner.execute { gate.await(2, TimeUnit.SECONDS) }
        runner.cancel()
        runner.execute { ran.set(true) }
        gate.countDown()
        service.awaitTermination(2, TimeUnit.SECONDS)
        org.junit.Assert.assertFalse("startup must not run after cancel", ran.get())
    }
}
