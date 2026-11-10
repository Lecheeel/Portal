package com.system.location.service.hook.utils

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeLifecycleTest {
    @Test fun sampleActivationAndScenarioUseOnePublicationAndOriginalDeadline() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot())
            FakeLoc.scenarioSamples.replace(emptyMap())
            val sample = LocationSample(Wgs84(31.0, 121.0), 10.0, 5f, 8f, 90f, 1000, 1000)
            assertThrows(IllegalArgumentException::class.java) { FakeLoc.acceptSample(sample, "x".repeat(256)) }
            assertFalse(FakeLoc.enable)
            assertNull(FakeLoc.runtimeSnapshots.current().sample)
            FakeLoc.acceptSample(sample, "route")
            val captured = FakeLoc.captureTransfer(2000)
            assertTrue(captured.externallyDriven)
            assertTrue(captured.state.configuration.enabled)
            assertEquals(1L, captured.state.configRevision)
            assertSame(sample, captured.state.sample)
            assertEquals(mapOf("route" to sample), captured.scenarios)
            assertTrue(FakeLoc.captureTransfer(5_000_002_000).scenarios.isEmpty())
        } finally {
            FakeLoc.scenarioSamples.replace(emptyMap())
            FakeLoc.updateCoordinates(0.0, 0.0, nowNanos = 0)
            FakeLoc.runtimeSnapshots.set(saved)
        }
    }

    @Test fun restoringMovementDoesNotRestartItsActivityWindow() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot(configuration = HookConfiguration(enabled = true)))
            FakeLoc.updateCoordinates(31.0, 121.0, 4f, 1_000_000_000)
            val transfer = FakeLoc.captureTransfer(1_100_000_000)
            FakeLoc.restoreRuntime(transfer, 1_200_000_000)
            assertEquals(4f, FakeLoc.snapshot(true, 1_200_000_000, 1000).speed)
            FakeLoc.restoreRuntime(transfer, 2_000_000_000)
            assertEquals(0f, FakeLoc.snapshot(true, 2_000_000_000, 2000).speed)
            FakeLoc.restoreRuntime(transfer, 999_999_999)
            assertEquals(0f, FakeLoc.snapshot(true, 999_999_999, 2000).speed)
        } finally {
            FakeLoc.updateCoordinates(0.0, 0.0, nowNanos = 0)
            FakeLoc.runtimeSnapshots.set(saved)
        }
    }

    @Test fun fullRestoreReplacesPolicyAndScenariosTogetherAndDoesNotReviveStaleSamples() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            val sample = LocationSample(Wgs84(31.0, 121.0), 10.0, 5f, 8f, 90f, 1000, 1_000_000_000)
            val transfer = RuntimeTransfer(RuntimeSnapshot(configRevision = 7, sample = sample,
                configuration = HookConfiguration(enabled = true, minimumSatellites = 24, nmeaEnabled = false)),
                true, mapOf("route" to sample))
            FakeLoc.restoreRuntime(transfer, 2_000_000_000)
            assertTrue(FakeLoc.externallyDriven)
            assertSame(sample, FakeLoc.snapshot(true, 2_000_000_000, 2000))
            val captured = FakeLoc.captureTransfer(2_000_000_000)
            assertEquals(transfer, captured)
            FakeLoc.restoreRuntime(transfer, 6_000_000_000)
            assertFalse(FakeLoc.enable)
            assertFalse(FakeLoc.externallyDriven)
            assertTrue(FakeLoc.scenarioSamples.scenarioIds().isEmpty())
            val stale = FakeLoc.captureTransfer(6_000_000_000)
            assertNull(stale.state.sample)
            assertEquals(24, stale.state.configuration.minimumSatellites)
        } finally {
            FakeLoc.scenarioSamples.replace(emptyMap())
            FakeLoc.updateCoordinates(0.0, 0.0, nowNanos = 0)
            FakeLoc.runtimeSnapshots.set(saved)
        }
    }

    @Test fun startDropsPreviousExternalFixAndMovementBeforeAnyNewSampleIsRead() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot())
            FakeLoc.acceptSample(LocationSample(Wgs84(31.0, 121.0), 10.0, 5f, 8f, 90f, 1000, 1000))
            assertTrue(FakeLoc.externallyDriven)
            val started = FakeLoc.startRuntime(RuntimeStart(Wgs84(40.0, 116.0), 3.0, 20.0, 8f), 2000)
            assertFalse(FakeLoc.externallyDriven)
            assertTrue(started.configuration.enabled)
            assertNull(started.sample)
            val sample = FakeLoc.snapshot(true, 3000, 3000)
            assertEquals(Wgs84(40.0, 116.0), sample.coordinate)
            assertEquals(20.0, sample.altitude, 0.0)
            assertEquals(8f, sample.accuracy)
            assertEquals(0f, sample.speed)
        } finally {
            FakeLoc.updateCoordinates(0.0, 0.0, nowNanos = 0)
            FakeLoc.runtimeSnapshots.set(saved)
        }
    }

    @Test fun stopPublishesDisabledPolicyAndClearedBearingTogether() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot(configRevision = 4, hasBearings = true,
                configuration = HookConfiguration(enabled = true, minimumSatellites = 20)))
            val stopped = FakeLoc.stopRuntime()
            assertFalse(stopped.configuration.enabled)
            assertFalse(stopped.hasBearings)
            assertEquals(5L, stopped.configRevision)
            assertEquals(20, stopped.configuration.minimumSatellites)
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }
}
