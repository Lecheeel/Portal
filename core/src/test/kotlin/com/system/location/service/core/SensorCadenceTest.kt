package com.system.location.service.core

import com.system.location.service.core.sensors.SensorCadence
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCadenceTest {
    @Test
    fun detectorBatchUsesModeledCadenceAndEndsAtCurrentTime() {
        val timestamps = SensorCadence.detectorEventTimestamps(
            nowNanos = 5_000_000_000L,
            eventCount = 3,
            cadencePerMinute = 120,
        )

        assertArrayEquals(longArrayOf(4_000_000_000L, 4_500_000_000L, 5_000_000_000L), timestamps)
    }

    @Test
    fun detectorBatchRemainsAfterPreviouslyDeliveredEvent() {
        val timestamps = SensorCadence.detectorEventTimestamps(
            nowNanos = 10_000_000_000L,
            eventCount = 2,
            cadencePerMinute = 120,
            previousEventNanos = 9_900_000_000L,
        )

        assertTrue(timestamps.first() > 9_900_000_000L)
        assertArrayEquals(longArrayOf(9_950_000_000L, 10_000_000_000L), timestamps)
    }

    @Test
    fun zeroSizedDetectorBatchHasNoTimestamps() {
        assertArrayEquals(longArrayOf(), SensorCadence.detectorEventTimestamps(
            nowNanos = 1_000_000_000L,
            eventCount = 0,
            cadencePerMinute = 120,
        ))
    }
}
