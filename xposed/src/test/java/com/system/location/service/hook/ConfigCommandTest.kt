package com.system.location.service.hook

import com.system.location.service.core.runtime.*
import com.system.location.service.hook.utils.FakeLoc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ConfigCommandTest {
    @Test fun invalidCaptureOrScalarNeverChangesPublishedState() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            val initial = RuntimeSnapshot(configuration = HookConfiguration(enabled = true, minimumSatellites = 20))
            FakeLoc.runtimeSnapshots.set(initial)
            for (invalid in listOf(mapOf("gnss_capture" to "invalid"), mapOf("min_satellites" to -1),
                mapOf("routing_json" to "invalid"), mapOf("enable_nmea" to 0))) {
                assertThrows(IllegalArgumentException::class.java) {
                    ConfigCommand.publish(mapOf("enable" to false, "enable_agps" to true) + invalid)
                }
                assertSame(initial, FakeLoc.runtimeSnapshots.current())
            }
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }

    @Test fun concurrentReadersObserveOnlyCompleteBatches() {
        val saved = FakeLoc.runtimeSnapshots.current()
        val executor = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        val started = CountDownLatch(1)
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot())
            val reader = executor.submit {
                started.countDown()
                while (!done.get()) {
                    val config = FakeLoc.runtimeSnapshots.current().configuration
                    assertEquals(if (config.enabled) 25 else 0, config.minimumSatellites)
                    assertEquals(!config.enabled, config.nmeaEnabled)
                    assertEquals(config.enabled, config.allowPhysicalRawGnss)
                }
            }
            started.await(5, TimeUnit.SECONDS)
            repeat(2_000) { i ->
                val enabled = i % 2 == 0
                ConfigCommand.publish(mapOf("enable" to enabled, "min_satellites" to if (enabled) 25 else 0,
                    "enable_nmea" to !enabled, "enable_agps" to enabled))
            }
            done.set(true)
            reader.get(10, TimeUnit.SECONDS)
            assertEquals(2_000L, FakeLoc.runtimeSnapshots.current().configRevision)
        } finally {
            done.set(true)
            executor.shutdownNow()
            FakeLoc.runtimeSnapshots.set(saved)
        }
    }
}
