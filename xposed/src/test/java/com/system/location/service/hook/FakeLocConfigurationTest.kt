package com.system.location.service.hook

import com.system.location.service.core.runtime.HookConfiguration
import com.system.location.service.hook.utils.FakeLoc
import org.junit.Assert.*
import org.junit.Test

class FakeLocConfigurationTest {
    @Test fun legacyAccessorsReadOneAuthoritativeConfigurationAndPreserveSnapshotFields() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            val old = saved.copy(configuration = HookConfiguration(), latitude = 31.0, longitude = 121.0)
            FakeLoc.runtimeSnapshots.set(old)
            FakeLoc.enable = true
            FakeLoc.minSatellites = 27
            FakeLoc.allowPhysicalRawGnss = true
            FakeLoc.enableNMEA = false
            val changed = FakeLoc.runtimeSnapshots.current()
            assertTrue(changed.configuration.enabled)
            assertEquals(27, changed.configuration.minimumSatellites)
            assertTrue(changed.configuration.allowPhysicalRawGnss)
            assertFalse(changed.configuration.nmeaEnabled)
            assertEquals(31.0 to 121.0, FakeLoc.coordinatePair())
            assertFalse(old.configuration.enabled)
            assertEquals(59, old.configuration.minimumSatellites)
            assertTrue(changed.configRevision > old.configRevision)
            FakeLoc.runtimeSnapshots.set(changed.copy(configuration = HookConfiguration(minimumSatellites = 5)))
            assertFalse(FakeLoc.enable)
            assertEquals(5, FakeLoc.minSatellites)
            assertFalse(FakeLoc.allowPhysicalRawGnss)
            assertTrue(FakeLoc.enableNMEA)
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }

    @Test fun invalidSetterCannotPublishOrLosePreviouslyAcceptedPolicy() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            val accepted = saved.copy(configuration = HookConfiguration(enabled = true, minimumSatellites = 7))
            FakeLoc.runtimeSnapshots.set(accepted)
            assertThrows(IllegalArgumentException::class.java) { FakeLoc.minSatellites = -1 }
            assertSame(accepted, FakeLoc.runtimeSnapshots.current())
            FakeLoc.enableMockGnss = false
            assertTrue(FakeLoc.enable)
            assertEquals(7, FakeLoc.minSatellites)
            assertFalse(FakeLoc.enableMockGnss)
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }
}
