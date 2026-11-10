package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssClockEvidenceTest {
    private val clock = GnssMeasurementBatch(1, 123, -456, 0.25, 2, emptyList(),
        timeUncertaintyNanos = 0.0, biasUncertaintyNanos = 3.5, driftNanosPerSecond = -0.2,
        driftUncertaintyNanosPerSecond = 0.01, leapSecond = 18, elapsedRealtimeNanos = 1_000_000,
        elapsedRealtimeUncertaintyNanos = 1000.0, capturedElapsedRealtimeNanos = 1_500_000)
    private val nav = GnssNavigationObservation(1, 0x0101, 1, 1, -1, 1, "AQ==", 2_000_000)
    private fun capture(batch: GnssMeasurementBatch = clock, message: GnssNavigationObservation = nav) =
        GnssCapture(deviceModel = "device", osVersion = "os", startedAtMillis = 1, endedAtMillis = 2,
            frames = listOf(GnssObservationFrame(1, satellites = emptyList())),
            measurements = listOf(batch), navigation = listOf(message))

    @Test fun localCodecPreservesAllClockEvidenceButAuthenticatedStatusWireExcludesIt() {
        val decoded = GnssCaptureCodec.decode(GnssCaptureCodec.encode(capture()))
        assertEquals(clock, decoded.measurements.single())
        assertEquals(nav, decoded.navigation.single())
        val wire = GnssCaptureWire.decode(GnssCaptureWire.encode(capture()))
        assertTrue(wire.measurements.isEmpty())
        assertTrue(wire.navigation.isEmpty())
    }

    @Test fun olderCapturesRetainUnknownClockFieldsInsteadOfInventingDefaults() {
        val old = capture(GnssMeasurementBatch(1, 123, -456, null, 2, emptyList()),
            GnssNavigationObservation(1, 0x0101, 1, 1, -1, 1, "AQ=="))
        val decoded = GnssCaptureCodec.decode(GnssCaptureCodec.encode(old))
        assertNull(decoded.measurements.single().elapsedRealtimeNanos)
        assertNull(decoded.measurements.single().biasUncertaintyNanos)
        assertNull(decoded.measurements.single().leapSecond)
        assertNull(decoded.navigation.single().capturedElapsedRealtimeNanos)
    }

    @Test fun suppliedUncertaintyAndMonotonicEpochsMustBeValid() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01)) {
            assertThrows(IllegalArgumentException::class.java) { clock.copy(timeUncertaintyNanos = bad) }
            assertThrows(IllegalArgumentException::class.java) { clock.copy(biasUncertaintyNanos = bad) }
            assertThrows(IllegalArgumentException::class.java) { clock.copy(driftUncertaintyNanosPerSecond = bad) }
            assertThrows(IllegalArgumentException::class.java) { clock.copy(elapsedRealtimeUncertaintyNanos = bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { clock.copy(elapsedRealtimeNanos = -1) }
        assertThrows(IllegalArgumentException::class.java) { clock.copy(capturedElapsedRealtimeNanos = -1) }
        assertThrows(IllegalArgumentException::class.java) { nav.copy(capturedElapsedRealtimeNanos = -1) }
    }
}
