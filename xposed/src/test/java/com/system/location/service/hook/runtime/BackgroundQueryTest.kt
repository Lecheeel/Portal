package com.system.location.service.hook.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class BackgroundQueryTest {
    @Test fun aBlockedQueryDoesNotBlockTheCallerOrQueueMorePolls() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val query = BackgroundQuery(executor)
        val caller = Thread.currentThread()
        var worker: Thread? = null
        try {
            assertTrue(query.submit({
                worker = Thread.currentThread()
                entered.countDown()
                release.await()
                42
            }) { result -> assertEquals(42, result.getOrThrow()); received.countDown() })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotSame(caller, worker)
            repeat(100) { assertFalse(query.submit({ error("must not queue") }) { }) }
            release.countDown()
            assertTrue(received.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun aFailedReadIsDeliveredAndTheNextPollCanRun() {
        val tasks = ArrayDeque<Runnable>()
        val query = BackgroundQuery { tasks.add(it) }
        val failure = IllegalStateException("service unavailable")
        var received: Throwable? = null
        assertTrue(query.submit({ throw failure }) { received = it.exceptionOrNull() })
        tasks.removeFirst().run()
        assertSame(failure, received)
        assertTrue(query.submit({ 42 }) { assertEquals(42, it.getOrThrow()) })
        tasks.removeFirst().run()
    }

    @Test fun aRejectedSubmissionDoesNotPermanentlySuppressPolling() {
        var reject = true
        val tasks = ArrayDeque<Runnable>()
        val query = BackgroundQuery {
            if (reject) throw RejectedExecutionException()
            tasks.add(it)
        }
        assertThrows(RejectedExecutionException::class.java) { query.submit({ 1 }) { } }
        reject = false
        assertTrue(query.submit({ 2 }) { assertEquals(2, it.getOrThrow()) })
        tasks.removeFirst().run()
    }
}
