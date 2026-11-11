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
    @Test fun disablingConfigurationCannotLeaveAnExternalFixToBeRevivedLater() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot())
            val fix = com.system.location.service.core.location.LocationSample(
                com.system.location.service.core.geo.Wgs84(31.0, 121.0), 10.0, 5f, 4f, 90f, 1000, 1000)
            FakeLoc.acceptSample(fix, "route")
            val stopped = ConfigCommand.publish(mapOf("enable" to false, "min_satellites" to 24))
            assertFalse(FakeLoc.externallyDriven)
            assertNull(stopped.sample)
            assertEquals(24, stopped.configuration.minimumSatellites)
            assertTrue(FakeLoc.scenarioSamples.scenarioIds().isEmpty())
            ConfigCommand.publish(mapOf("enable" to true))
            assertFalse(FakeLoc.captureTransfer(2000).externallyDriven)
        } finally {
            FakeLoc.stopRuntime()
            FakeLoc.runtimeSnapshots.set(saved)
        }
    }

    @Test fun invalidWireTypeOrScalarNeverChangesPublishedState() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            val initial = RuntimeSnapshot(configuration = HookConfiguration(enabled = true, minimumSatellites = 20))
            FakeLoc.runtimeSnapshots.set(initial)
            for (invalid in listOf(mapOf("gnss_capture" to 123), mapOf("min_satellites" to -1),
                mapOf("routing_json" to "invalid"), mapOf("enable_nmea" to 0))) {
                assertThrows(IllegalArgumentException::class.java) {
                    ConfigCommand.publish(mapOf("enable" to false, "enable_agps" to true) + invalid)
                }
                assertSame(initial, FakeLoc.runtimeSnapshots.current())
            }
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }

    @Test fun corruptOptionalCaptureStillPublishesCompleteRequiredConfiguration() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot())
            val result = ConfigCommand.publish(mapOf("gnss_capture" to "invalid",
                "min_satellites" to 24, "enable_nmea" to false))
            assertEquals(24, result.configuration.minimumSatellites)
            assertFalse(result.configuration.nmeaEnabled)
            assertTrue(result.configuration.gnssCaptureRejected)
            assertNull(result.configuration.gnssSource)
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }

    @Test fun concurrentReadersObserveOnlyCompleteBatches() {
        val saved = FakeLoc.runtimeSnapshots.current()
        val executor = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        val started = CountDownLatch(1)
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot(configuration = HookConfiguration(minimumSatellites = 0)))
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
