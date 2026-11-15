package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationBatchTimeline
import com.system.location.service.core.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationBatchTimelineTest {
    private val endpoint = LocationSample(
        coordinate = Wgs84(31.0, 121.0),
        altitude = 20.0,
        accuracy = 5f,
        speed = 2f,
        bearing = 90f,
        timeMillis = 10_000L,
        elapsedNanos = 10_000_000_000L,
    )

    @Test fun batchUsesSyntheticMotionAndPairedMonotonicClocks() {
        val batch = LocationBatchTimeline.synthesize(endpoint, count = 3, intervalMillis = 1_000L)

        assertEquals(3, batch.size)
        assertEquals(listOf(8_000L, 9_000L, 10_000L), batch.map { it.timeMillis })
        assertEquals(listOf(8_000_000_000L, 9_000_000_000L, 10_000_000_000L),
            batch.map { it.elapsedNanos })
        assertNotEquals(batch[0].coordinate, batch[1].coordinate)
        assertNotEquals(batch[1].coordinate, batch[2].coordinate)
        assertEquals(endpoint.coordinate, batch.last().coordinate)
        assertTrue(batch.all { it.speed == endpoint.speed && it.bearing == endpoint.bearing })
    }

    @Test fun stationaryBatchKeepsCoordinatesButStillHasDistinctTimes() {
        val batch = LocationBatchTimeline.synthesize(endpoint.copy(speed = 0f), count = 3, intervalMillis = 500L)

        assertEquals(listOf(endpoint.coordinate, endpoint.coordinate, endpoint.coordinate),
            batch.map { it.coordinate })
        assertEquals(listOf(9_000L, 9_500L, 10_000L), batch.map { it.timeMillis })
    }

    @Test fun insufficientClockHistoryFallsBackToOneValidEndpoint() {
        val recent = endpoint.copy(timeMillis = 1L, elapsedNanos = 1L)

        assertEquals(listOf(recent), LocationBatchTimeline.synthesize(recent, count = 4, intervalMillis = 1_000L))
    }

    @Test fun oversizedBatchesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationBatchTimeline.synthesize(endpoint, LocationBatchTimeline.MAX_BATCH_SIZE + 1, 100L)
        }
    }
}
