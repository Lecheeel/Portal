package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class CaptureGpsClockAssociationTest {
    private fun batch(elapsed: Long, gpsNanos: Long = 1316L * 604800_000_000_000L) =
        GnssMeasurementBatch(1, 0, -gpsNanos, 0.0, 0, emptyList(), timeUncertaintyNanos = 0.0,
            biasUncertaintyNanos = 10.0, elapsedRealtimeNanos = elapsed,
            elapsedRealtimeUncertaintyNanos = 10.0, capturedElapsedRealtimeNanos = elapsed)

    @Test fun associationDistanceUsesIntegerNanosecondsAtLongUptimes() {
        val elapsed = 9_007_199_254_740_992L // beyond exact nanosecond resolution of Double.
        val clock = CaptureGpsClockAssociation(listOf(batch(elapsed)))
        assertNotNull(clock.atArrival(elapsed + 10_000_000_000L).reference)
        assertNull(clock.atArrival(elapsed + 10_000_000_001L).reference)
        assertNotNull(clock.atArrival(elapsed - 10_000_000_000L).reference)
        assertNull(clock.atArrival(elapsed - 10_000_000_001L).reference)
    }

    @Test fun invalidEraAndUnknownOrDelayedClockEvidenceReturnDiagnosticInsteadOfThrowing() {
        val b = batch(20_000_000_000L)
        for (invalid in listOf(b.copy(biasUncertaintyNanos = null), b.copy(elapsedRealtimeNanos = null),
            b.copy(capturedElapsedRealtimeNanos = b.elapsedRealtimeNanos!! + 10_000_000_001L))) {
            assertNull(CaptureGpsClockAssociation(listOf(invalid)).atArrival(b.elapsedRealtimeNanos).reference)
        }
        val clock = CaptureGpsClockAssociation(listOf(batch(20_000_000_000L, gpsNanos = 0)))
        val beforeOrigin = clock.atArrival(19_999_999_999L)
        assertNull(beforeOrigin.reference)
        assertNotNull(beforeOrigin.reason)
        assertNull(clock.atArrival(null).reference)
    }
}
