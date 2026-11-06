package com.system.location.service.core

import com.system.location.service.core.config.ConfigEnvelope
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.InMemoryConfigStore
import com.system.location.service.core.runtime.InMemorySampleStore
import com.system.location.service.core.runtime.SampleStore
import com.system.location.service.core.scenario.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleStoreTest {
    private val sample = LocationSample(
        coordinate = Wgs84(31.2304, 121.4737),
        altitude = 80.0,
        accuracy = 8f,
        speed = 5f,
        bearing = 90f,
        timeMillis = 1_790_000_000_000,
        elapsedNanos = 1_000_000_000,
    )

    @Test
    fun publishReturnsMonotonicSequenceAndLatest() {
        val store = InMemorySampleStore()
        assertEquals(1L, store.publish(sample, 100L))
        assertEquals(2L, store.publish(sample.copy(timeMillis = 2), 200L))
        val latest = store.latest(nowElapsedNanos = 200L)!!
        assertEquals(2L, latest.sequence)
        assertEquals(2L, latest.sample.timeMillis)
        assertEquals(200L, latest.storedAtElapsedNanos)
    }

    @Test
    fun staleLeaseReturnsNull() {
        val store = InMemorySampleStore()
        store.publish(sample, 1_000L)
        assertTrue(store.latest(nowElapsedNanos = 1_000L, maxAgeNanos = 0L) != null)
        assertNull(store.latest(nowElapsedNanos = 2_000L, maxAgeNanos = 500L))
        assertTrue(store.latest(nowElapsedNanos = 1_500L, maxAgeNanos = 1_000L) != null)
        assertNull(store.latest(nowElapsedNanos = 999L))
    }

    @Test
    fun clearRemovesLatest() {
        val store = InMemorySampleStore()
        store.publish(sample, 1_000L)
        store.clear()
        assertNull(store.latest(1_000L))
    }

    @Test
    fun defaultLeaseIsFiveSeconds() {
        val store = InMemorySampleStore()
        store.publish(sample, 0L)
        assertTrue(store.latest(nowElapsedNanos = SampleStore.DEFAULT_MAX_AGE_NANOS) != null)
        assertNull(store.latest(nowElapsedNanos = SampleStore.DEFAULT_MAX_AGE_NANOS + 1))
    }

    @Test
    fun configStoreRoundTripsAndClears() {
        val config = ConfigEnvelope(
            revision = 1,
            scenario = Scenario("p", "p", point = Wgs84(31.0, 121.0)),
            updatedAtMillis = 1,
        )
        val store = InMemoryConfigStore()
        assertNull(store.load())
        store.save(config)
        assertEquals(config, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun initialConfigIsExposed() {
        val config = ConfigEnvelope(
            revision = 2,
            scenario = Scenario("p", "p", point = Wgs84(31.0, 121.0)),
        )
        assertEquals(config, InMemoryConfigStore(config).load())
    }
}
