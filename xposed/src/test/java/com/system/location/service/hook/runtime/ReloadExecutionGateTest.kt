package com.system.location.service.hook.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ReloadExecutionGateTest {
    @Test fun activeCallDrainsAndQueuedOldCallEntersTheSuccessorInsteadOfOldCode() {
        val old = ReloadExecutionGate()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val frozen = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val oldBodies = AtomicInteger()
        val newBodies = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val active = Thread {
            runCatching { old.enter { assertTrue(it); inside.countDown(); release.await() } }
                .onFailure(failure::set)
        }.apply { start() }
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        val successor = AtomicReference<ReloadExecutionGate>()
        val writer = Thread {
            runCatching {
                assertTrue(old.freeze(2000))
                successor.set(ReloadExecutionGate.adopt(old.transferState()))
                frozen.countDown()
                committed.await()
                successor.get().resume()
            }.onFailure(failure::set)
        }.apply { start() }
        awaitFreeze(old)
        assertEquals("Freeze completed before its active reader exited", 1L, frozen.count)
        release.countDown()
        assertTrue(frozen.await(2, TimeUnit.SECONDS))
        val waiting = CountDownLatch(1)
        val queued = Thread {
            runCatching {
                waiting.countDown()
                old.enter { current ->
                    if (current) oldBodies.incrementAndGet()
                    else successor.get().enter { assertTrue(it); newBodies.incrementAndGet() }
                }
            }.onFailure(failure::set)
        }.apply { start() }
        assertTrue(waiting.await(2, TimeUnit.SECONDS))
        committed.countDown()
        for (thread in listOf(active, writer, queued)) { thread.join(3000); assertFalse(thread.isAlive) }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(0, oldBodies.get())
        assertEquals(1, newBodies.get())
    }

    @Test fun nestedFrameworkCallsCanFinishWhileFreezeWaits() {
        val gate = ReloadExecutionGate()
        val entered = CountDownLatch(1)
        val nested = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val active = Thread {
            runCatching {
                gate.enter {
                    entered.countDown()
                    nested.await()
                    gate.enter { assertTrue(it) }
                }
            }.onFailure(failure::set)
        }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val drained = AtomicBoolean()
        val writer = Thread {
            runCatching { drained.set(gate.freeze(2000)); gate.resume() }.onFailure(failure::set)
        }.apply { start() }
        awaitFreeze(gate)
        nested.countDown()
        active.join(3000); writer.join(3000)
        assertFalse(active.isAlive); assertFalse(writer.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertTrue(drained.get())
    }

    @Test fun failedFreezeResumesTheSameGenerationWithoutLosingReaders() {
        val gate = ReloadExecutionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = Thread { gate.enter { entered.countDown(); release.await() } }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(gate.freeze(0))
            gate.enter { assertTrue(it) }
        } finally { release.countDown(); active.join(2000) }
        assertFalse(active.isAlive)
        assertTrue(gate.freeze(1000))
        val state = gate.transferState()
        assertTrue((state[2] as Map<*, *>).isEmpty())
        assertNull(state.javaClass.classLoader)
        assertNull(state[0].javaClass.classLoader)
        assertNull(state[1].javaClass.classLoader)
        assertNull(state[2].javaClass.classLoader)
        gate.resume()
    }

    @Test fun successorMayRunOnAnotherThreadAndRetiredGenerationCannotThawIt() {
        val gate = ReloadExecutionGate()
        assertTrue(gate.freeze(1000))
        val state = gate.transferState()
        val failure = AtomicReference<Throwable?>()
        val next = Thread {
            runCatching {
                val successor = ReloadExecutionGate.adopt(state)
                successor.enter { assertTrue(it) } // New transition owner may install/restore hooks.
                assertTrue(runCatching { gate.resume() }.isFailure)
                successor.resume()
            }.onFailure(failure::set)
        }.apply { start() }
        next.join(2000)
        assertFalse(next.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        gate.enter { assertFalse(it) }
    }

    private fun awaitFreeze(gate: ReloadExecutionGate) {
        val started = System.nanoTime()
        while (!gate.isFrozen && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2)) Thread.yield()
        assertTrue("Writer did not start draining", gate.isFrozen)
    }
}
