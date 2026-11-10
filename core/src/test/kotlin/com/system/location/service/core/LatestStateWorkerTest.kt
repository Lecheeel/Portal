package com.system.location.service.core

import com.system.location.service.core.runtime.LatestStateWorker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LatestStateWorkerTest {
    @Test fun blockedReceiverOnlyGetsInFlightAndLatestCompleteState() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val received = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val worker = LatestStateWorker<Int>({ state ->
            received.add(state)
            if (state == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } else done.countDown()
        }, failures::add)
        try {
            assertTrue(worker.offer(1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            (2..10_000).forEach { assertTrue(worker.offer(it)) }
            assertEquals(listOf(1), received.toList())
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 10_000), received.toList())
            assertTrue(failures.isEmpty())
        } finally { release.countDown(); worker.close() }
    }

    @Test fun closeDiscardsPendingAndNeverRestartsWhileOldDeliveryIsInFlight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val received = CopyOnWriteArrayList<Int>()
        val worker = LatestStateWorker<Int>({ state ->
            received.add(state)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            done.countDown()
        }, { throw AssertionError(it) })
        try {
            worker.offer(1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.offer(2)
            worker.close()
            assertFalse(worker.offer(3))
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1), received.toList())
        } finally { release.countDown(); worker.close() }
    }

    @Test fun failedDeliveryClosesMailboxAndNotifiesOwner() {
        val failed = CountDownLatch(1)
        val error = IllegalStateException("dead peer")
        val worker = LatestStateWorker<Int>({ throw error }, { assertSame(error, it); failed.countDown() })
        worker.offer(1)
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertFalse(worker.offer(2))
    }
}
