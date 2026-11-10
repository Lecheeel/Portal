package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GpsElapsedClockEvidenceTest {
    private fun capture(): GnssCapture {
        val measurements = (0..3).map { i ->
            val elapsed = 100_000_000_000L + i * 10_000_000_000L
            val gps = 2400L * 604800_000_000_000L + (604770L + i * 10) * 1_000_000_000L
            GnssMeasurementBatch(i * 10000L, elapsed, elapsed - gps, 0.0, 0, emptyList(),
                timeUncertaintyNanos = 10.0, biasUncertaintyNanos = 10.0,
                elapsedRealtimeNanos = elapsed, elapsedRealtimeUncertaintyNanos = 10.0,
                capturedElapsedRealtimeNanos = elapsed + 1_000_000)
        }
        return GnssCapture(deviceModel = "test", osVersion = "test", startedAtMillis = 0, endedAtMillis = 30000,
            frames = listOf(GnssObservationFrame(0, satellites = emptyList())), measurements = measurements, bootCount = 42)
    }

    @Test fun advancesFullGpsWeekUsingOnlySameBootMonotonicTime() {
        val e = GpsElapsedClockEvidence.fromCapture(capture())!!
        assertEquals(GpsTime(2401, 0.0), e.at(130_000_000_000, 42))
        assertEquals(GpsTime(2401, 10.25), e.at(140_250_000_000, 42))
        assertNull(e.at(140_250_000_000, 43))
        assertNull(e.at(140_250_000_000, null))
        assertNull(e.at(129_999_999_999, 42))
        assertNotNull(e.at(1_930_000_000_000, 42))
        assertNull(e.at(1_930_000_000_001, 42))
        val changedWallClock = capture().copy(startedAtMillis = 1000000, endedAtMillis = 1030000,
            frames = listOf(GnssObservationFrame(1000000, satellites = emptyList())),
            measurements = capture().measurements.map { it.copy(capturedAtMillis = it.capturedAtMillis + 1000000) })
        assertEquals(e, GpsElapsedClockEvidence.fromCapture(changedWallClock))
    }

    @Test fun refusesMissingBootUnknownUncertaintiesResetInconsistencyAndInsufficientSpan() {
        val c = capture()
        val invalid = listOf(c.copy(bootCount = null), c.copy(measurements = c.measurements.take(2)),
            c.copy(measurements = c.measurements.map { it.copy(biasUncertaintyNanos = null) }),
            c.copy(measurements = c.measurements.map { it.copy(elapsedRealtimeNanos = null) }),
            c.copy(measurements = c.measurements.mapIndexed { i, b -> if (i == 1) b.copy(hardwareClockDiscontinuityCount = 1, fullBiasNanos = null) else b }),
            c.copy(measurements = c.measurements.mapIndexed { i, b -> if (i == 1) b.copy(fullBiasNanos = b.fullBiasNanos!! - 10_000_000) else b }))
        invalid.forEach { assertNull(GpsElapsedClockEvidence.fromCapture(it)) }
    }
}
