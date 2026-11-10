package com.system.location.service.hook.utils

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeLifecycleTest {
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
