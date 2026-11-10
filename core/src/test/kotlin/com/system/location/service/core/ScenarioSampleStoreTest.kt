package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.ScenarioSampleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioSampleStoreTest {
    private fun sample(lat: Double, elapsed: Long = 1_000L) = LocationSample(
        coordinate = Wgs84(lat, 121.47),
        altitude = 80.0,
        accuracy = 6f,
        speed = 1f,
        bearing = 90f,
        timeMillis = 1_000,
        elapsedNanos = elapsed,
    )

    @Test fun scenariosAreIsolated() {
        val store = ScenarioSampleStore()
        store.publish("a", sample(31.0), 100)
        store.publish("b", sample(32.0), 100)
        assertEquals(31.0, store.latest("a", 100)!!.sample.latitude, 0.0)
        assertEquals(32.0, store.latest("b", 100)!!.sample.latitude, 0.0)
        assertNull(store.latest("missing", 100))
    }

    @Test fun sequenceIsMonotonicAcrossScenarios() {
        val store = ScenarioSampleStore()
        assertEquals(1L, store.publish("a", sample(31.0), 100))
        assertEquals(2L, store.publish("b", sample(32.0), 100))
        assertEquals(3L, store.publish("a", sample(33.0), 200))
        assertEquals(3L, store.latest("a", 200)!!.sequence)
    }

    @Test fun staleAndFutureSamplesAreRejected() {
        val store = ScenarioSampleStore()
        store.publish("a", sample(31.0), 1_000)
        assertNull(store.latest("a", 2_000, maxAgeNanos = 500))
        assertNull(store.latest("a", 999))
        assertTrue(store.latest("a", 1_500, maxAgeNanos = 1_000) != null)
    }

    @Test fun oldestScenarioIsEvictedAtCapacity() {
        val store = ScenarioSampleStore(maxScenarios = 2)
        store.publish("a", sample(31.0), 100)
        store.publish("b", sample(32.0), 100)
        store.publish("c", sample(33.0), 100)
        assertEquals(setOf("b", "c"), store.scenarioIds())
        assertNull(store.latest("a", 100))
    }

    @Test fun clearAndValidation() {
        val store = ScenarioSampleStore()
        store.publish("a", sample(31.0), 100)
        store.clear("a")
        assertTrue(store.scenarioIds().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.publish("", sample(31.0), 100) }
        assertThrows(IllegalArgumentException::class.java) { ScenarioSampleStore(0) }
    }

    @Test fun tickSnapshotRetainsItsSamplesAndAppliesLeasesToAllScenarios() {
        val store = ScenarioSampleStore()
        store.publish("stale", sample(30.0), 100)
        store.publish("current", sample(31.0), 1_000)
        store.publish("future", sample(32.0), 2_000)
        val captured = store.snapshot(1_500, 500)
        assertEquals(setOf("current"), captured.keys)
        store.publish("current", sample(33.0), 1_500)
        store.clear("current")
        assertEquals(31.0, captured.getValue("current").latitude, 0.0)
        assertThrows(IllegalArgumentException::class.java) { store.snapshot(1_500, -1) }
    }
}
