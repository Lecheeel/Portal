package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeStartTest {
    @Test fun startReplacesTheFixAndEnablesItsPolicyInOneTransition() {
        val oldPoint = Wgs84(31.0, 121.0)
        val state = RuntimeSnapshot(configRevision = 9, latitude = 31.0, longitude = 121.0,
            sample = LocationSample(oldPoint, 10.0, 5f, 2f, 90f, 1000, 1000), provider = ProviderKind.GPS,
            physicalNoiseSeed = 42, configuration = HookConfiguration(nmeaEnabled = false, minimumSatellites = 24))
        val result = RuntimeStart(Wgs84(40.0, 116.0), 3.0, 20.0, 8f, 200).applyTo(state)
        assertTrue(result.configuration.enabled)
        assertEquals(40.0, result.latitude, 0.0)
        assertEquals(116.0, result.longitude, 0.0)
        assertEquals(3.0, result.speedMps, 0.0)
        assertEquals(20.0, result.altitudeMeters, 0.0)
        assertEquals(8f, result.accuracyMeters)
        assertEquals(200L, result.configuration.reportIntervalMs)
        assertNull(result.sample)
        assertNull(result.provider)
        assertEquals(10L, result.configRevision)
        assertFalse(result.configuration.nmeaEnabled)
        assertEquals(24, result.configuration.minimumSatellites)
        assertEquals(42L, result.physicalNoiseSeed)
        assertFalse(state.configuration.enabled)
    }

    @Test fun omittedFieldsUseLatestStateAndRepeatedCasEvaluationHasNoSideEffects() {
        val start = RuntimeStart(Wgs84(1.0, 2.0))
        val latest = RuntimeSnapshot(configRevision = 4, speedMps = 7.0, altitudeMeters = 33.0,
            accuracyMeters = 12f, configuration = HookConfiguration(allowPhysicalRawGnss = true))
        val result = start.applyTo(latest)
        assertEquals(7.0, result.speedMps, 0.0)
        assertEquals(33.0, result.altitudeMeters, 0.0)
        assertEquals(12f, result.accuracyMeters)
        assertTrue(result.configuration.allowPhysicalRawGnss)
        assertEquals(result, start.applyTo(latest))
    }

    @Test fun invalidStartCannotChangeExistingState() {
        val store = RuntimeSnapshotStore()
        val before = store.current()
        val invalid = listOf<() -> RuntimeStart>(
            { RuntimeStart(Wgs84(1.0, 2.0), speedMps = -1.0) },
            { RuntimeStart(Wgs84(1.0, 2.0), altitudeMeters = Double.NaN) },
            { RuntimeStart(Wgs84(1.0, 2.0), accuracyMeters = 0f) },
            { RuntimeStart(Wgs84(1.0, 2.0), reportIntervalMs = 0) },
        )
        invalid.forEach { prepare ->
            assertThrows(IllegalArgumentException::class.java) { store.update(prepare()::applyTo) }
            assertSame(before, store.current())
        }
    }
}
