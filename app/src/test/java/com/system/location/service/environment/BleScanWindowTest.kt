package com.system.location.service.environment

import com.system.location.service.core.environment.BleBeacon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BleScanWindowTest {
    private fun beacon(address: String) = BleBeacon(address, "test", -60)

    @Test fun timeoutReturnsCollectedBeaconsAndStopsScan() = runBlocking {
        var stopped = false
        val result = collectBleScanWindow(
            timeoutMillis = 10,
            maxResults = 256,
            startScan = { onResult, _ ->
                onResult(beacon("00:11:22:33:44:55"))
                onResult(beacon("00:11:22:33:44:66"))
            },
            stopScan = { stopped = true },
        )

        assertEquals(2, result.size)
        assertTrue(stopped)
    }

    @Test fun resultLimitCompletesEarlyAndDeduplicatesByAddress() = runBlocking {
        var stopped = false
        val result = collectBleScanWindow(
            timeoutMillis = 5_000,
            maxResults = 2,
            startScan = { onResult, _ ->
                onResult(beacon("00:11:22:33:44:55"))
                onResult(beacon("00:11:22:33:44:55"))
                onResult(beacon("00:11:22:33:44:66"))
            },
            stopScan = { stopped = true },
        )

        assertEquals(listOf("00:11:22:33:44:55", "00:11:22:33:44:66"), result.map { it.address })
        assertTrue(stopped)
    }

    @Test fun cancellationStopsAnActiveScan() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val job = launch {
            collectBleScanWindow(
                timeoutMillis = 5_000,
                maxResults = 256,
                startScan = { _, _ -> started.complete(Unit) },
                stopScan = { stopped = true },
            )
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(stopped)
    }

    @Test fun scanFailureKeepsPartialResultsAndStopsScan() = runBlocking {
        var stopped = false
        val result = collectBleScanWindow(
            timeoutMillis = 5_000,
            maxResults = 256,
            startScan = { onResult, onFailure ->
                onResult(beacon("00:11:22:33:44:55"))
                onFailure()
            },
            stopScan = { stopped = true },
        )

        assertEquals(listOf("00:11:22:33:44:55"), result.map { it.address })
        assertTrue(stopped)
    }

    @Test fun synchronousStartFailureKeepsPartialResultsAndStopsScan() = runBlocking {
        var stopped = false
        val result = collectBleScanWindow(
            timeoutMillis = 5_000,
            maxResults = 256,
            startScan = { onResult, _ ->
                onResult(beacon("00:11:22:33:44:55"))
                throw SecurityException("scan permission revoked")
            },
            stopScan = { stopped = true },
        )

        assertEquals(listOf("00:11:22:33:44:55"), result.map { it.address })
        assertTrue(stopped)
    }

    @Test fun concurrentCallbacksNeverExceedResultLimit() = runBlocking {
        val callbacks = 24
        val maximum = 8
        val ready = CountDownLatch(callbacks)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(callbacks)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val result = collectBleScanWindow(
                timeoutMillis = 5_000,
                maxResults = maximum,
                startScan = { onResult, _ ->
                    val tasks = (0 until callbacks).map { index ->
                        executor.submit {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            onResult(beacon("00:11:22:33:44:${"%02X".format(index)}"))
                        }
                    }
                    check(ready.await(5, TimeUnit.SECONDS))
                    start.countDown()
                    tasks.forEach { it.get(5, TimeUnit.SECONDS) }
                },
                stopScan = { stopped.set(true) },
            )

            assertEquals(maximum, result.size)
            assertTrue(stopped.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
