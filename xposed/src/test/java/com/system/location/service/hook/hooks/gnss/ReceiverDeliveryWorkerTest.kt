package com.system.location.service.hook.hooks.gnss

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ReceiverDeliveryWorkerTest {
    @Test fun repeatedStartIsSingleWorkerAndStopAllowsRestart() {
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val worker = ReceiverDeliveryWorker({
            if (calls.incrementAndGet() == 1) first.countDown() else second.countDown()
        }, { throw AssertionError(it) })
        try {
            worker.start()
            assertTrue(first.await(2, TimeUnit.SECONDS))
            repeat(10) { worker.start() }
            assertEquals(1, calls.get())
            worker.stop()
            worker.start()
            assertTrue(second.await(2, TimeUnit.SECONDS))
            worker.stop()
            assertEquals(2, calls.get())
        } finally { worker.stop() }
    }

    @Test fun stopWaitsForInflightCallbackAndNoReplacementOverlapsIt() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = ReceiverDeliveryWorker({
            calls.incrementAndGet()
            entered.countDown()
            try { release.await() } catch (_: InterruptedException) {
                interrupted.countDown()
                release.await()
                Thread.currentThread().interrupt()
            }
        }, { throw AssertionError(it) })
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stopper = Thread { worker.stop() }.apply { start() }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            worker.start()
            assertEquals(1, calls.get())
            release.countDown()
            stopper.join(2000)
            assertFalse(stopper.isAlive)
        } finally { release.countDown(); worker.stop() }
    }
}
