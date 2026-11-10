package com.system.location.service.hook.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DeliveryWorkerTest {
    @Test fun publicationDuringBlockedTickIsCoalescedAndDeliveredWithoutWaitingForTimer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val next = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = worker {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            } else next.countDown()
        }
        try {
            worker.requestTick() // A publication must not create an unregistered producer.
            assertEquals(0, calls.get())
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { worker.requestTick() }
            release.countDown()
            assertTrue("Must not wait for the 60 second fallback timer", next.await(2, TimeUnit.SECONDS))
            assertTrue(worker.stop())
            assertEquals(2, calls.get())
        } finally { release.countDown(); worker.stop() }
    }

    @Test fun publicationWakesASleepingProducerAndStopStillCancelsItsWait() {
        val sleeping = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val worker = DeliveryWorker("PublicationWakeup", {
            sleeping.countDown()
            60_000
        }, delayFirstTick = true, tick = { delivered.countDown() }, failure = { throw AssertionError(it) })
        try {
            worker.start()
            assertTrue(sleeping.await(2, TimeUnit.SECONDS))
            worker.requestTick()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertTrue(worker.stop())
            worker.requestTick()
        } finally { worker.stop() }
    }

    @Test fun repeatedStartAndConfirmedStop() {
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val worker = worker {
            if (calls.incrementAndGet() == 1) first.countDown() else second.countDown()
        }
        try {
            worker.start()
            assertTrue(first.await(2, TimeUnit.SECONDS))
            repeat(10) { worker.start() }
            assertEquals(1, calls.get())
            assertTrue(worker.stop())
            worker.start()
            assertTrue(second.await(2, TimeUnit.SECONDS))
            assertTrue(worker.stop())
            assertEquals(2, calls.get())
        } finally { worker.stop() }
    }

    @Test fun timedOutStopRetainsRestartUntilBlockedCallbackReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val worker = worker {
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    // A Binder callback may consume interrupts without returning.
                    while (release.count != 0L) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                } else restarted.countDown()
            } finally { active.decrementAndGet() }
        }
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.stop(20))
            repeat(10) { worker.start() }
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(restarted.await(2, TimeUnit.SECONDS))
            assertTrue(worker.stop())
            assertEquals(2, calls.get())
            assertEquals(1, peak.get())
        } finally { release.countDown(); worker.stop() }
    }

    @Test fun anotherStopCancelsPendingRestart() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = worker {
            calls.incrementAndGet()
            entered.countDown()
            while (release.count != 0L) {
                try { release.await() } catch (_: InterruptedException) { }
            }
        }
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.stop(0))
            worker.start()
            assertFalse(worker.stop(0))
            release.countDown()
            assertTrue(worker.stop())
            assertEquals(1, calls.get())
        } finally { release.countDown(); worker.stop() }
    }

    @Test fun selfStopDoesNotJoinItselfAndDelayCanBeCancelled() {
        val stopped = CountDownLatch(1)
        lateinit var worker: DeliveryWorker
        worker = worker { assertFalse(worker.stop()); stopped.countDown() }
        worker.start()
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertTrue(worker.stop())

        val calls = AtomicInteger()
        val delayed = DeliveryWorker("Delayed", { 60_000 }, true,
            { calls.incrementAndGet() }, { throw AssertionError(it) })
        delayed.start()
        assertTrue(delayed.stop())
        assertEquals(0, calls.get())
    }

    private fun worker(tick: () -> Unit) = DeliveryWorker("TestDelivery", { 60_000 },
        tick = tick, failure = { throw AssertionError(it) })
}
