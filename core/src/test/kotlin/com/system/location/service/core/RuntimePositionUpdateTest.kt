package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimePositionUpdateTest {
    @Test fun oneUpdateDropsCachedFixAndReplacesPositionAndOptionalHeadingTogether() {
        val original = RuntimeSnapshot(configRevision = 3, bearingDeg = 15.0, configuration = HookConfiguration(enabled = true),
            sample = LocationSample(Wgs84(30.0, 120.0), 10.0, 5f, 0f, 0f, 1000, 1000))
        val update = RuntimePositionUpdate(Wgs84(31.0, 121.0), 4f, 270.0)
        val state = update.applyTo(original)
        assertEquals(31.0, state.latitude, 0.0)
        assertEquals(121.0, state.longitude, 0.0)
        assertEquals(270.0, state.bearingDeg, 0.0)
        assertTrue(state.hasBearings)
        assertNull(state.sample)
        assertNull(state.provider)
        assertEquals(4L, state.configRevision)
        assertSame(original.configuration, state.configuration)
        assertEquals(15.0, update.copy(bearingDeg = null).applyTo(original).bearingDeg, 0.0)
    }

    @Test fun invalidMotionMetadataCannotReachPublication() {
        for (speed in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { RuntimePositionUpdate(Wgs84(0.0, 0.0), speed) }
        }
        assertThrows(IllegalArgumentException::class.java) { RuntimePositionUpdate(Wgs84(0.0, 0.0), bearingDeg = Double.NaN) }
    }
}
