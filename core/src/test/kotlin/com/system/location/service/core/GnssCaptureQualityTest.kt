package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssCaptureQualityTest {
    private fun capture() = GnssCapture(deviceModel = "receiver", osVersion = "os",
        startedAtMillis = 1000, endedAtMillis = 61000,
        frames = (0..60).map { index -> GnssObservationFrame(1000L + index * 1000,
            observer = Wgs84(31.0, 121.0), satellites = listOf(
                GnssSatellite(Constellation.GPS, 1, 30f, 100f, 35f, true, true, true))) })

    @Test fun validCaptureHasAnExplicitEligibilityResult() {
        assertTrue(GnssCaptureValidator.validate(capture(), 62000).eligible)
    }

    @Test fun sparseStaleOrUnlocatedEvidenceCannotDriveSimulation() {
        assertFalse(GnssCaptureValidator.validate(capture().copy(frames = capture().frames.take(1)), 62000).eligible)
        assertFalse(GnssCaptureValidator.validate(capture(), 62000 + GnssCaptureValidator.MAX_AGE_MILLIS).eligible)
        assertFalse(GnssCaptureValidator.validate(capture().copy(frames = capture().frames.map { it.copy(observer = null) }), 62000).eligible)
    }

    @Test fun correlatedRepeatsDoNotCreateFalsePrecisionInShortCaptures() {
        val short = capture().frames.take(5)
        val profile = GnssObservationProfileBuilder.build(short, "device", "os").constellations.single()
        assertEquals(0.0, profile.ratioConfidenceLow, 0.0)
        assertEquals(1.0, profile.ratioConfidenceHigh, 0.0)
    }
}
