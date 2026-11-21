package com.system.location.service.hook.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DeferredServiceConnectionTest {
    @Test fun startupReturnsWhileBinderWorkIsBlockedOnAnotherThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        var worker: Thread? = null
        val executor = Executors.newSingleThreadScheduledExecutor()
        val finished = CountDownLatch(1)
        val job = DeferredServiceConnection(
            schedule = { delay, task -> executor.schedule({ task() }, delay, TimeUnit.MILLISECONDS); Unit },
            connect = {
                worker = Thread.currentThread()
                entered.countDown()
                release.await()
                finished.countDown()
                true
            },
            onFailure = { throw AssertionError(it) },
        )
        try {
            job.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotSame(caller, worker)
            assertEquals(1L, finished.count)
            job.start() // Coalesce callbacks while the first Binder call is blocked.
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun unavailableServiceAndBinderExceptionRetryThenStopAfterAcknowledgment() {
        val tasks = ArrayDeque<() -> Unit>()
        val delays = mutableListOf<Long>()
        val failures = mutableListOf<Exception>()
        var calls = 0
        val job = DeferredServiceConnection(
            schedule = { delay, task -> delays.add(delay); tasks.add(task) },
            connect = { when (++calls) {
                1 -> false
                2 -> throw IllegalStateException("service starting")
                else -> true
            } },
            onFailure = failures::add,
        )
        job.start()
        job.start()
        assertEquals(0, calls)
        assertEquals(1, tasks.size)
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        assertEquals(3, calls)
        assertEquals(1, failures.size)
        assertEquals(listOf(0L, 250L, 500L), delays)
        job.start()
        assertTrue(tasks.isEmpty())
    }

    @Test fun repeatedFailureIsBoundedAndAllowsAnExplicitLaterRetry() {
        val tasks = ArrayDeque<() -> Unit>()
        var calls = 0
        val job = DeferredServiceConnection(
            schedule = { _, task -> tasks.add(task) },
            connect = { calls++; false },
            onFailure = { throw AssertionError(it) },
            maxAttempts = 3,
        )
        job.start()
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        assertEquals(3, calls)
        job.start()
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        assertEquals(6, calls)
    }
}
