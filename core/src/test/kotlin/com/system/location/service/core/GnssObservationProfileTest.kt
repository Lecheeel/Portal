package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssObservationFrame
import com.system.location.service.core.gnss.GnssObservationProfileBuilder
import com.system.location.service.core.gnss.GnssSatellite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssObservationProfileTest {
    @Test
    fun aggregatesConstellationRatiosAndDistributions() {
        val frames = listOf(
            frame(1_000L, listOf(
                satellite(Constellation.GPS, 1, 30f, 35f, true),
                satellite(Constellation.BEIDOU, 1, 20f, 28f, true),
            )),
            frame(2_000L, listOf(
                satellite(Constellation.GPS, 1, 40f, 42f, true),
                satellite(Constellation.GPS, 2, 10f, 18f, false),
                satellite(Constellation.BEIDOU, 1, 25f, 31f, true),
            )),
        )

        val profile = GnssObservationProfileBuilder.build(frames, "test-device", "test-os")

        assertEquals(2, profile.frameCount)
        assertEquals(5, profile.totalSatelliteSamples)
        assertEquals(3, profile.profileFor(Constellation.GPS)!!.satelliteSamples)
        assertEquals(0.6, profile.profileFor(Constellation.GPS)!!.ratio, 0.000_001)
        assertEquals(1, profile.profileFor(Constellation.GPS)!!.frameCountHistogram[1])
        assertEquals(1, profile.profileFor(Constellation.GPS)!!.frameCountHistogram[2])
        assertEquals(2, profile.profileFor(Constellation.GPS)!!.usedSamples)
        assertTrue(profile.profileFor(Constellation.GPS)!!.ratioConfidenceLow < 0.6)
        assertTrue(profile.profileFor(Constellation.GPS)!!.ratioConfidenceHigh > 0.6)
        assertEquals(listOf(1, 2), profile.profileFor(Constellation.GPS)!!.identities.map { it.svid })
    }

    @Test
    fun frameOrderDoesNotChangeTheProfile() {
        val first = frame(10_000L, listOf(satellite(Constellation.GALILEO, 7, 50f, 40f, true)))
        val second = frame(1_000L, listOf(satellite(Constellation.GPS, 3, 45f, 39f, true)))

        val profile = GnssObservationProfileBuilder.build(listOf(first, second), "device", "os")

        assertEquals(1_000L, profile.capturedAtMillis)
        assertEquals(10_000L, profile.captureEndedAtMillis)
        assertEquals(0.5, profile.profileFor(Constellation.GPS)!!.ratio, 0.000_001)
        assertEquals(0.5, profile.profileFor(Constellation.GALILEO)!!.ratio, 0.000_001)
    }

    @Test
    fun emptyCaptureIsRejected() {
        try {
            GnssObservationProfileBuilder.build(emptyList(), "device", "os")
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected empty capture to be rejected")
    }

    private fun frame(time: Long, satellites: List<GnssSatellite>) = GnssObservationFrame(
        capturedAtMillis = time,
        observer = Wgs84(31.2304, 121.4737),
        altitudeMeters = 80.0,
        satellites = satellites,
    )

    private fun satellite(
        constellation: Constellation,
        svid: Int,
        elevation: Float,
        cn0: Float,
        used: Boolean,
    ) = GnssSatellite(
        constellation = constellation,
        svid = svid,
        elevationDeg = elevation,
        azimuthDeg = 120f,
        cn0DbHz = cn0,
        usedInFix = used,
        hasEphemeris = true,
        hasAlmanac = true,
        carrierHz = 1.57542e9f,
    )
}
