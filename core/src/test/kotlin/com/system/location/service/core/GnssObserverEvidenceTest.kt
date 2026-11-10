package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssObserverEvidenceTest {
    private val frame = GnssObservationFrame(1000, Wgs84(31.0, 121.0), 20.0,
        satellites = emptyList(), capturedElapsedRealtimeNanos = 100_000_000_000L,
        observerElapsedRealtimeNanos = 99_000_000_000L,
        observerElapsedRealtimeUncertaintyNanos = 100.0, observerHorizontalAccuracyMeters = 5.0,
        observerVerticalAccuracyMeters = 8.0)
    private fun capture(frame: GnssObservationFrame) = GnssCapture(deviceModel = "test", osVersion = "test",
        startedAtMillis = 1000, endedAtMillis = 1000, frames = listOf(frame), bootCount = 42)

    @Test fun retainedObserverEpochAndAccuracyRemainDistinctFromStatusArrival() {
        val c = capture(frame)
        assertEquals(c, GnssCaptureCodec.decode(GnssCaptureCodec.encode(c)))
        assertEquals(c, GnssCaptureWire.decode(GnssCaptureWire.encode(c)))
        assertNotEquals(frame.capturedElapsedRealtimeNanos, frame.observerElapsedRealtimeNanos)
        val legacy = GnssCaptureCodec.decode("""{"deviceModel":"old","osVersion":"old",
            "startedAtMillis":1000,"endedAtMillis":1000,
            "frames":[{"capturedAtMillis":1000,"satellites":[]}]}""")
        assertNull(legacy.frames.single().capturedElapsedRealtimeNanos)
        assertNull(legacy.frames.single().observerElapsedRealtimeNanos)
        assertNull(legacy.frames.single().observerHorizontalAccuracyMeters)
    }

    @Test fun contradictoryOrNonfiniteObserverEvidenceCannotEnterAStoredCapture() {
        assertThrows(IllegalArgumentException::class.java) { frame.copy(observerElapsedRealtimeNanos = -1) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(observerElapsedRealtimeNanos = 100_000_000_001L) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(capturedElapsedRealtimeNanos = null) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(observer = null) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(altitudeMeters = null) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(observerElapsedRealtimeUncertaintyNanos = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(observerHorizontalAccuracyMeters = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(observerVerticalAccuracyMeters = Double.POSITIVE_INFINITY) }
    }
}
