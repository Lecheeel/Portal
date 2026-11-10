package com.system.location.service.hook.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ReloadPreparationTest {
    @Test fun confirmedHandoffStopsBothProducersAndDoesNotResumeTheRetiredGeneration() {
        val order = mutableListOf<String>()
        assertTrue(ReloadPreparation.prepare({ order += "location"; true }, { order += "gnss"; true },
            { order += "save" }, { fail("Successful handoff must stay quiesced") }))
        assertEquals(listOf("location", "gnss", "save"), order)
    }

    @Test fun failureOfEitherPauseDoesNotSaveAndRollsBackOnce() {
        for (location in listOf(false, true)) for (gnss in listOf(false, true)) {
            if (location && gnss) continue
            val calls = AtomicInteger()
            val aborts = AtomicInteger()
            assertFalse(ReloadPreparation.prepare({ calls.incrementAndGet(); location },
                { calls.incrementAndGet(); gnss }, { fail("Cannot save live delivery") }, { aborts.incrementAndGet() }))
            assertEquals(2, calls.get())
            assertEquals(1, aborts.get())
        }
    }

    @Test fun snapshotFailureRetainsTheOriginalErrorAndRollsBack() {
        val failure = IllegalStateException("snapshot failed")
        val aborts = AtomicInteger()
        val actual = assertThrows(IllegalStateException::class.java) {
            ReloadPreparation.prepare({ true }, { true }, { throw failure }, { aborts.incrementAndGet() })
        }
        assertSame(failure, actual)
        assertEquals(1, aborts.get())
    }

    @Test fun timedOutBinderDeliveryResumesAfterReturnWithoutOverlappingReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val worker = DeliveryWorker("Reload rollback regression", { 60_000 }, tick = {
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    while (release.count > 0L) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                } else resumed.countDown()
            } finally { active.decrementAndGet() }
        }, failure = { throw AssertionError(it) })
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(ReloadPreparation.prepare({ worker.stop(0) }, { true },
                { fail("In-flight callback must prevent handoff") }, { worker.start() }))
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(resumed.await(2, TimeUnit.SECONDS))
            assertTrue(worker.stop())
            assertEquals(2, calls.get())
            assertEquals(1, peak.get())
        } finally { release.countDown(); worker.stop() }
    }

    @Test fun rollbackFailureDoesNotHideTheOriginalSnapshotFailure() {
        val snapshot = IllegalStateException("snapshot failed")
        val rollback = IllegalStateException("rollback failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            ReloadPreparation.prepare({ true }, { true }, { throw snapshot }, { throw rollback })
        }
        assertSame(snapshot, actual)
        assertArrayEquals(arrayOf(rollback), actual.suppressed)
    }
}
