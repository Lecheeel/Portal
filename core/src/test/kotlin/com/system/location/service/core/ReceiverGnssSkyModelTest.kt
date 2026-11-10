package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverGnssSkyModelTest {
    private val observer = Wgs84(31.0, 121.0)
    private fun satellite(id: Int, system: Constellation = Constellation.GPS) =
        GnssSatellite(system, id, 20f + id, 100f, 30f + id, true, true, true)
    private fun capture() = GnssCapture(deviceModel = "device", osVersion = "os", startedAtMillis = 1000, endedAtMillis = 61000,
        frames = (0..60).map { index -> GnssObservationFrame(1000L + index * 1000, observer,
            satellites = buildList {
                add(satellite(1).copy(azimuthDeg = if (index < 30) 359f else 1f))
                add(satellite(1, Constellation.BEIDOU))
                if (index % 4 == 0) add(satellite(2))
            }) })

    @Test fun automaticCountsAndConstellationSamplesFollowTheCapture() {
        val model = ReceiverGnssSkyModel(capture())
        // A complete reverse traversal retains every captured frame's joint empirical distribution.
        val observed = (0..60).map { model.frame(61000L + it * 1000, observer, 0.0)!! }
        assertEquals(capture().frames.sumOf { it.satellites.size }, observed.sumOf { it.satellites.size })
        assertEquals(setOf(2, 3), observed.map { it.satellites.size }.toSet())
        assertTrue(observed.all { it.source == ReceiverGnssSource.STATISTICAL_REPLAY })
    }

    @Test fun minimumUsesOnlyObservedIdentitiesAndReportsUnavailableCapacity() {
        val model = ReceiverGnssSkyModel(capture())
        assertEquals(2, model.frame(62000, observer, 0.0)!!.satellites.size)
        assertEquals(3, model.frame(62000, observer, 0.0, 3)!!.satellites.size)
        val excessive = model.frame(62000, observer, 0.0, 30)!!
        assertEquals(3, excessive.satellites.size)
        assertTrue(excessive.diagnostics.any { it.contains("无法满足") })
        assertNull(excessive.geometry.dop)
    }

    @Test fun interpolationCrossesNorthViaTheShortestArcAndIsDeterministic() {
        val model = ReceiverGnssSkyModel(capture(), 0)
        val first = model.frame(91500, observer, 0.0)!!
        assertEquals(first, model.frame(91500, observer, 0.0))
        assertEquals(0f, first.satellites.first().azimuthDeg, 1e-5f)
        assertTrue(first.satellites.all { it.elevationDeg >= 0 && it.azimuthDeg < 360 })
    }

    @Test fun invalidOrExpiredCaptureReturnsNoFrame() {
        val expired = ReceiverGnssSkyModel(capture()).frame(61001 + GnssCaptureValidator.MAX_AGE_MILLIS, observer, 0.0)
        assertNull(expired)
        val sparse = capture().copy(frames = capture().frames.take(1))
        assertNull(ReceiverGnssSkyModel(sparse).frame(62000, observer, 0.0))
    }
}
