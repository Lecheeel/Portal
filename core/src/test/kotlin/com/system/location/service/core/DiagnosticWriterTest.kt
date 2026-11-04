package com.system.location.service.core

import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.repository.DocumentStore
import com.system.location.service.core.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class DiagnosticWriterTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: break).run() }
    }

    private fun event(i: Long) = DiagnosticEvent(i, BackendType.XPOSED, "HOOK_POINT", "INFO", "hook $i")

    @Test fun rejectedEnqueueRetriesFullFailedQueueWithoutAcceptingTheRejectedBatch() = runBlocking {
        val dispatcher = ManualDispatcher()
        val store = object : DocumentStore {
            var text: String? = null
            var fail = true
            var writes = 0
            override fun read() = text
            override fun writeAtomically(text: String) {
                writes++
                if (fail) error("disk full")
                this.text = text
            }
        }
        val writer = DiagnosticWriter(DiagnosticJournal(store), capacity = 2, dispatcher = dispatcher)
        try {
            writer.enqueue(listOf(event(1), event(2)))
            dispatcher.drain()
            assertEquals(1, store.writes)
            assertNotNull(writer.failure.value)
            assertEquals(2, writer.pendingCount)
            assertNull(store.text)

            store.fail = false
            assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
                writer.enqueue(listOf(event(99), event(100)))
            }
            assertEquals(2, writer.pendingCount)
            assertEquals(1, store.writes)
            assertNull(store.text)
            dispatcher.drain()
            // Check before flush/close: the rejected enqueue itself must wake the worker.
            assertEquals("next enqueue must retry retained failed data", 0, writer.pendingCount)
            assertEquals(2, store.writes)
            assertNull(writer.failure.value)
            assertEquals(listOf(1L, 2L), DiagnosticJournal(store).read().map { it.timestamp })

            // Rejection must not consume capacity or change the accepted-event barrier.
            writer.enqueue(listOf(event(3), event(4)))
            assertEquals(2, writer.pendingCount)
            assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
                writer.enqueue(listOf(event(101)))
            }
            assertEquals(2, writer.pendingCount)
            dispatcher.drain()
            assertEquals(0, writer.pendingCount)
            assertEquals(listOf(1L, 2L, 3L, 4L), DiagnosticJournal(store).read().map { it.timestamp })
        } finally {
            store.fail = false
            val closing = async(Dispatchers.Unconfined) { writer.close() }
            dispatcher.drain()
            closing.await().getOrThrow()
        }
        assertTrue(writer.isClosed)
    }

    @Test fun capacityIncludesInFlightEventsAndRejectsWholeBatch() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = object : DocumentStore {
            @Volatile var text: String? = null
            override fun read() = text
            override fun writeAtomically(text: String) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                this.text = text
            }
        }
        val writer = DiagnosticWriter(DiagnosticJournal(store), capacity = 3)
        try {
            writer.enqueue(listOf(event(1), event(2)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
                writer.enqueue(listOf(event(3), event(4)))
            }
            assertEquals(2, writer.pendingCount)
            writer.enqueue(listOf(event(5)))
            assertEquals(3, writer.pendingCount)
        } finally { release.countDown(); writer.close().getOrThrow() }
        assertEquals(listOf(1L, 2L, 5L), DiagnosticJournal(store).read().map { it.timestamp })
    }

    @Test fun concurrentFlushAndProducersUseOneWriterWithoutLosingAcceptedEvents() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val store = object : DocumentStore {
            @Volatile var text: String? = null
            override fun read() = text
            override fun writeAtomically(text: String) {
                val count = active.incrementAndGet()
                maxActive.accumulateAndGet(count, ::maxOf)
                try { this.text = text } finally { active.decrementAndGet() }
            }
        }
        val writer = DiagnosticWriter(DiagnosticJournal(store), capacity = 300)
        try {
            coroutineScope {
                (1L..120L).map { i -> async(Dispatchers.Default) {
                    writer.enqueue(listOf(event(i)))
                    if (i % 10L == 0L) writer.flush().getOrThrow()
                } }.awaitAll()
            }
            writer.flush().getOrThrow()
            assertEquals((1L..120L).toSet(), DiagnosticJournal(store).read().map { it.timestamp }.toSet())
            assertEquals(120, DiagnosticJournal(store).read().size)
            assertEquals(1, maxActive.get())
        } finally { writer.close().getOrThrow() }
    }

    @Test fun cancelledCallerStillFlushesAcceptedEventsBeforeReturning() = runBlocking {
        val store = object : DocumentStore {
            @Volatile var text: String? = null
            override fun read() = text
            override fun writeAtomically(text: String) { this.text = text }
        }
        val writer = DiagnosticWriter(DiagnosticJournal(store))
        writer.enqueue((1L..60L).map(::event))
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            writer.flush()
        }
        caller.join()
        assertEquals(60, DiagnosticJournal(store).read().size)
        writer.close().getOrThrow()
    }

    @Test fun failedCloseReleasesWorkerAndRetainsDataForExplicitRetry() = runBlocking {
        val store = object : DocumentStore {
            @Volatile var fail = true
            @Volatile var text: String? = null
            override fun read() = text
            override fun writeAtomically(text: String) {
                if (fail) error("disk full")
                this.text = text
            }
        }
        val writer = DiagnosticWriter(DiagnosticJournal(store))
        writer.enqueue((1L..60L).map(::event))
        assertTrue(writer.close().isFailure)
        assertTrue(writer.isClosed)
        assertNotNull(writer.failure.value)
        assertEquals(60, writer.pendingCount)
        assertNull(store.text)
        store.fail = false
        writer.close().getOrThrow()
        assertEquals((1L..60L).toList(), DiagnosticJournal(store).read().map { it.timestamp })
        assertNull(writer.failure.value)
        assertEquals(0, writer.pendingCount)
    }

    @Test fun slowDiskDoesNotBlockProducerAndClosePersistsTheFinalBatch() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val store = object : DocumentStore {
            @Volatile var text: String? = null
            override fun read() = text
            override fun writeAtomically(text: String) {
                writes.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                this.text = text
            }
        }
        val writer = DiagnosticWriter(DiagnosticJournal(store), capacity = 120)
        try {
            writer.enqueue((1L..60L).map(::event))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Disk remains blocked while a second accepted batch is enqueued.
            withTimeout(1000) { withContext(Dispatchers.Default) { writer.enqueue((61L..120L).map(::event)) } }
            assertEquals(120, writer.pendingCount)
        } finally {
            release.countDown()
            writer.close().getOrThrow()
        }
        assertEquals((1L..120L).toList(), DiagnosticJournal(store).read().map { it.timestamp })
        assertTrue(writes.get() <= 2)
        assertEquals(0, writer.pendingCount)
        assertThrows(IllegalStateException::class.java) { writer.enqueue(listOf(event(121))) }
        Unit
    }
}
