package com.system.location.service.hook.utils

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.RuntimeSnapshot
import org.junit.Assert.*
import org.junit.Test

class ProviderViewTest {
    private val global = LocationSample(Wgs84(31.0, 121.0), 10.0, 5f, 2f, 90f, 1000, 1000)
    private val scenario = global.copy(coordinate = Wgs84(32.0, 122.0), altitude = 20.0)

    @Test fun contextRetainsItsPolicyAndAllRouteSamplesAfterLiveStateChanges() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            val samples = mutableMapOf("route" to scenario)
            val context = ProviderReadContext(RuntimeSnapshot(sample = global), samples, 1000)
            samples["route"] = scenario.copy(coordinate = Wgs84(40.0, 120.0))
            FakeLoc.runtimeSnapshots.set(RuntimeSnapshot(sample = global.copy(coordinate = Wgs84(50.0, 120.0)),
                physicalNoise = true, physicalNoiseSeed = 123))
            val gps = ProviderView.sampleFor("gps", context = context)
            assertEquals(global.coordinate, gps.coordinate)
            assertEquals(scenario.coordinate, ProviderView.sampleFor("gps", scenarioId = "route", context = context).coordinate)
            assertEquals(global.coordinate, ProviderView.sampleFor("gps", scenarioId = "missing", context = context).coordinate)
            val point = ProviderView.sampleFor("gps", point = Wgs84(33.0, 123.0), context = context)
            assertEquals(Wgs84(33.0, 123.0), point.coordinate)
            assertEquals(0f, point.speed)
            assertEquals(0f, point.bearing)
            assertSame(gps, ProviderView.sampleFor("gps", context = context))
            assertEquals(1000L, context.validationAtMillis)
            assertFalse(context.state.physicalNoise)
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }

    @Test fun allProvidersUseOneBaseAndRetainTheirExistingFieldPolicies() {
        val context = ProviderReadContext(RuntimeSnapshot(sample = global), emptyMap(), 1000)
        val gps = ProviderView.sampleFor("gps", context = context)
        val network = ProviderView.sampleFor("network", context = context)
        assertEquals(global.coordinate, gps.coordinate)
        assertEquals(global.coordinate, network.coordinate)
        assertEquals(global.timeMillis - 400, network.timeMillis)
        assertEquals(0.0, network.altitude, 0.0)
        assertEquals(0f, network.speed)
        assertSame(global, ProviderView.sampleFor("unknown", context = context))
    }
}
