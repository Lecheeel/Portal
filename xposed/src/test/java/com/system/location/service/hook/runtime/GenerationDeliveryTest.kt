package com.system.location.service.hook.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class GenerationDeliveryTest {
    @Test fun queuedOldProducerSkipsAfterGateIsTransferredButNewProducerRuns() {
        val gate = ReloadExecutionGate()
        val old = GenerationDelivery(gate)
        val worker = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sends = AtomicInteger()
        try {
            worker.submit { ready.countDown(); release.await() }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            val queued = worker.submit { old.run { sends.incrementAndGet() } }
            assertTrue(gate.freeze(1000))
            val successor = ReloadExecutionGate.adopt(gate.transferState())
            release.countDown()
            successor.resume()
            queued.get(1, TimeUnit.SECONDS)
            assertEquals(0, sends.get())
            worker.submit { GenerationDelivery(successor).run { sends.incrementAndGet() } }.get(1, TimeUnit.SECONDS)
            assertEquals(1, sends.get())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun runningProducerPreventsFreezeAndAbortLeavesSameGenerationUsable() {
        val gate = ReloadExecutionGate()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sends = AtomicInteger()
        try {
            val job = worker.submit { GenerationDelivery(gate).run {
                entered.countDown(); release.await(); sends.incrementAndGet()
            } }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(gate.freeze(10))
            release.countDown()
            job.get(1, TimeUnit.SECONDS)
            GenerationDelivery(gate).run { sends.incrementAndGet() }
            assertEquals(2, sends.get())
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
