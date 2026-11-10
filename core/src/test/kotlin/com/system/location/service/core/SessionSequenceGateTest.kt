package com.system.location.service.core

import com.system.location.service.core.runtime.SessionSequenceGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SessionSequenceGateTest {
    @Test fun oldSessionsDuplicatesAndOlderStatesCannotRestore() {
        val gate = SessionSequenceGate()
        var value = 0
        fun restore(session: String, sequence: Long) = gate.apply(session, sequence) { value++; true }
        assertFalse(restore("a", 1))
        gate.reset("a")
        assertFalse(restore("a", 0))
        assertTrue(restore("a", 2))
        assertFalse(restore("a", 1))
        assertFalse(restore("a", 2))
        gate.reset("b")
        assertFalse(restore("a", 100))
        assertTrue(restore("b", 1))
        assertEquals(2, value)
    }

    @Test fun failedValidationDoesNotConsumeSequence() {
        val gate = SessionSequenceGate()
        gate.reset("a")
        assertFalse(gate.apply("a", 10) { false })
        assertThrows(IllegalStateException::class.java) { gate.apply("a", 10) { error("bad state") } }
        assertTrue(gate.apply("a", 10) { true })
    }

    @Test fun concurrentNewerStateCannotBeOverwrittenByDelayedOlderRestore() {
        val gate = SessionSequenceGate()
        gate.reset("a")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var value = 0
        try {
            val first = executor.submit<Boolean> { gate.apply("a", 1) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                value = 1; true
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> { gate.apply("a", 2) { value = 2; true } }
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertTrue(second.get(5, TimeUnit.SECONDS))
            assertEquals(2, value)
            assertFalse(gate.apply("a", 1) { value = 1; true })
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
