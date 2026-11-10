package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssObserverGeometryTest {
    @Test fun geodeticCoordinatesMatchAnalyticEquatorPolesAndEllipsoidHeight() {
        val a = 6378137.0
        val b = 6356752.314245179 // WGS 84 semi-minor axis.
        assertPosition(EcefPosition(a, 0.0, 0.0), GnssObserverGeometry.ecef(Wgs84(0.0, 0.0), 0.0))
        assertPosition(EcefPosition(0.0, a + 100, 0.0), GnssObserverGeometry.ecef(Wgs84(0.0, 90.0), 100.0))
        assertPosition(EcefPosition(0.0, 0.0, b), GnssObserverGeometry.ecef(Wgs84(90.0, 0.0), 0.0))
        assertPosition(EcefPosition(0.0, 0.0, -b - 100), GnssObserverGeometry.ecef(Wgs84(-90.0, 45.0), 100.0))
        assertPosition(EcefPosition(-a, 0.0, 0.0), GnssObserverGeometry.ecef(Wgs84(0.0, 180.0), 0.0))
    }

    @Test fun cardinalDirectionsUseEastNorthUpAndClockwiseAzimuth() {
        val observer = Wgs84(0.0, 0.0)
        // At this point x is up, y east and z north; these displacements are independent ENU vectors.
        val cases = listOf(
            Triple(EcefPosition(6378137.0, 0.0, 1000.0), 0.0, 0.0),
            Triple(EcefPosition(6378137.0, 1000.0, 0.0), 90.0, 0.0),
            Triple(EcefPosition(6378137.0, 0.0, -1000.0), 180.0, 0.0),
            Triple(EcefPosition(6378137.0, -1000.0, 0.0), 270.0, 0.0),
            Triple(EcefPosition(6379137.0, 1000.0, 0.0), 90.0, 45.0),
            Triple(EcefPosition(6377137.0, 1000.0, 0.0), 90.0, -45.0),
        )
        cases.forEach { (point, azimuth, elevation) ->
            val los = GnssObserverGeometry.lineOfSight(observer, 0.0, point)!!
            assertEquals(azimuth, los.azimuthDegrees!!, 1e-12)
            assertEquals(elevation, los.elevationDegrees, 1e-12)
            assertEquals(elevation >= 0, los.aboveHorizon)
            assertEquals(los.rangeMeters * los.rangeMeters,
                los.eastMeters * los.eastMeters + los.northMeters * los.northMeters + los.upMeters * los.upMeters, 1e-8)
        }
    }

    @Test fun zenithNadirAndCoincidentPositionDoNotInventAzimuthOrRange() {
        val observer = Wgs84(42.0, -70.0)
        val zenith = GnssObserverGeometry.lineOfSight(observer, 100.0,
            GnssObserverGeometry.ecef(observer, 20_000_100.0))!!
        assertEquals(90.0, zenith.elevationDegrees, 1e-12)
        assertEquals(20_000_000.0, zenith.rangeMeters, 1e-8)
        assertNull(zenith.azimuthDegrees)
        val nadir = GnssObserverGeometry.lineOfSight(observer, 100.0,
            GnssObserverGeometry.ecef(observer, -999_900.0))!!
        assertEquals(-90.0, nadir.elevationDegrees, 1e-12)
        assertNull(nadir.azimuthDegrees)
        assertFalse(nadir.aboveHorizon)
        assertNull(GnssObserverGeometry.lineOfSight(observer, 100.0, GnssObserverGeometry.ecef(observer, 100.0)))
    }

    @Test fun rejectsNonFiniteCoordinatesAndPreservesDateLineEquivalence() {
        assertThrows(IllegalArgumentException::class.java) { GnssObserverGeometry.ecef(Wgs84(0.0, 0.0), Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { EcefPosition(Double.POSITIVE_INFINITY, 0.0, 0.0) }
        assertPosition(GnssObserverGeometry.ecef(Wgs84(20.0, 180.0), -100.0),
            GnssObserverGeometry.ecef(Wgs84(20.0, -180.0), -100.0))
    }

    private fun assertPosition(expected: EcefPosition, actual: EcefPosition) {
        assertEquals(expected.xMeters, actual.xMeters, 1e-8)
        assertEquals(expected.yMeters, actual.yMeters, 1e-8)
        assertEquals(expected.zMeters, actual.zMeters, 1e-8)
    }
}
