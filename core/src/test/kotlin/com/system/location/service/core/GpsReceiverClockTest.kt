package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GpsReceiverClockTest {
    private fun clock(time: Long, fullBias: Long?, bias: Double?) =
        GnssMeasurementBatch(1, time, fullBias, bias, 4, emptyList())

    @Test fun integerSubtractionPreservesFractionalBiasAtModernGpsEpochs() {
        val result = GpsReceiverClock.decode(clock(100_000_000_123, -1_399_507_100_000_000_000, 0.25)).epoch!!
        assertEquals(2314, result.time.week)
        assertEquals(0.00000012275, result.time.secondsOfWeek, 1e-22)
        assertEquals(4, result.hardwareDiscontinuityCount)
        assertNull(result.timeUncertaintySeconds)
    }

    @Test fun fractionalBiasCanMoveEpochIntoPreviousWeekWithoutLosingPrecision() {
        val result = GpsReceiverClock.decode(clock(100_000_000_000, -1_399_507_100_000_000_000, 0.25)).epoch!!
        assertEquals(2313, result.time.week)
        assertEquals(604800.0 - 0.25e-9, result.time.secondsOfWeek, 0.0)
        assertEquals(GpsTime(2314, 0.0), GpsReceiverClock.decode(clock(100_000_000_000,
            -1_399_507_100_000_000_000, null)).epoch!!.time)
    }

    @Test fun missingFullBiasOrNegativeGpsTimeCannotBecomeAnEpoch() {
        assertNull(GpsReceiverClock.decode(clock(123, null, null)).epoch)
        assertNull(GpsReceiverClock.decode(clock(0, 1, null)).epoch)
        // Integer arithmetic also handles subtracting Long.MIN_VALUE without signed overflow.
        assertNotNull(GpsReceiverClock.decode(clock(Long.MAX_VALUE, Long.MIN_VALUE, null)).epoch)
    }

    @Test fun reportedUncertaintyIsPreservedWithoutAssumingIndependentClockErrors() {
        val batch = clock(100_000_000_000, -1_399_507_100_000_000_000, null).copy(
            timeUncertaintyNanos = 3.0, biasUncertaintyNanos = 4.0,
            elapsedRealtimeNanos = 1_000, elapsedRealtimeUncertaintyNanos = 5.0)
        val epoch = GpsReceiverClock.decode(batch).epoch!!
        assertEquals(7e-9, epoch.timeUncertaintySeconds!!, 0.0)
        assertEquals(5e-9, epoch.elapsedRealtimeUncertaintySeconds!!, 0.0)
        assertEquals(1_000L, epoch.elapsedRealtimeNanos)
        assertNull(GpsReceiverClock.decode(batch.copy(biasUncertaintyNanos = null)).epoch!!.timeUncertaintySeconds)
    }
}
