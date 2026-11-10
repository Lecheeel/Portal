package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssLocationExtrasTest {
    private val satellites = (0..4).map { index ->
        GnssSatellite(Constellation.GPS, index + 1, if (index == 4) 90f else 30f,
            index * 90f % 360, 20f + index * 5, true, true, true)
    } + GnssSatellite(Constellation.GPS, 6, 50f, 10f, 60f, false, true, true)
    private val publication = PublishedGnssFrame(1, ReceiverGnssFrame(1000, Wgs84(31.0, 121.0),
        10.0, satellites, GnssGeometry.solve(satellites), ReceiverGnssSource.STATISTICAL_REPLAY), 0.0, 0.0)

    @Test fun usesOnlyParticipatingSatellitesAndTheirMeasuredCn0() {
        assertNotNull(publication.frame.geometry.dop)
        val extras = GnssLocationExtras.from(publication)
        assertEquals(5, extras.satellites)
        assertEquals(30f, extras.meanCn0DbHz!!, 0f)
        assertEquals(40f, extras.maxCn0DbHz!!, 0f)
    }

    @Test fun noSolutionDoesNotReportParticipatingSatellitesOrInventCn0() {
        val extras = GnssLocationExtras.from(publication.copy(frame = publication.frame.copy(
            geometry = GnssGeometryResult(null, "rank deficient"))))
        assertEquals(0, extras.satellites)
        assertNull(extras.meanCn0DbHz)
        assertNull(extras.maxCn0DbHz)
    }

    @Test fun zeroCn0UsedRowsAreNotPublishedAsMeasuredFixSignals() {
        val zero = GnssSatellite(Constellation.GALILEO, 1, 40f, 20f, 0f, true, true, true)
        val frame = publication.frame.copy(
            satellites = publication.frame.satellites + zero,
            availableSatelliteCapacity = publication.frame.availableSatelliteCapacity + 1,
        )
        val extras = GnssLocationExtras.from(publication.copy(frame = frame))
        assertEquals(5, extras.satellites)
        assertEquals(30f, extras.meanCn0DbHz!!, 0f)
        assertEquals(40f, extras.maxCn0DbHz!!, 0f)
    }
}
