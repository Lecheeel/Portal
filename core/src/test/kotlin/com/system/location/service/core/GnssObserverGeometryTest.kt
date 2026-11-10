package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssObserverGeometryTest {
    @Test fun nearNorthGeometryRemainsValidAfterStatusFloatConversion() {
        val los = GnssObserverGeometry.lineOfSight(EcefPosition(0.0, 0.0, 0.0),
            GeodeticOrientation(0.0, 0.0), EcefPosition(1.0, -1e-8, 1.0))!!
        val azimuth = los.azimuthDegrees!!
        assertTrue(azimuth < 360.0)
        assertEquals(360f, azimuth.toFloat(), 0f)
        assertEquals(0f, azimuth.toGnssAzimuthFloat(), 0f)
        assertEquals(90f, 90.0.toGnssAzimuthFloat(), 0f)
    }

    @Test fun explicitFrameProjectionIsTranslationInvariantAndUsesLocalNormal() {
        // At lat=0, lon=90: east=-x, north=z, up=y. Origin is intentionally not WGS84.
        val origin = EcefPosition(100.0, 200.0, 300.0)
        val orientation = GeodeticOrientation(0.0, 90.0)
        val target = EcefPosition(97.0, 212.0, 304.0)
        val los = GnssObserverGeometry.lineOfSight(origin, orientation, target)!!
        assertEquals(3.0, los.eastMeters, 1e-12)
        assertEquals(4.0, los.northMeters, 1e-12)
        assertEquals(12.0, los.upMeters, 1e-12)
        assertEquals(13.0, los.rangeMeters, 1e-12)
        val translated = GnssObserverGeometry.lineOfSight(EcefPosition(1100.0, -800.0, 2300.0),
            orientation, EcefPosition(1097.0, -788.0, 2304.0))!!
        assertEquals(los, translated)
        assertNull(GnssObserverGeometry.lineOfSight(origin, orientation, origin))
        assertThrows(IllegalArgumentException::class.java) { GeodeticOrientation(90.1, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GeodeticOrientation(0.0, Double.NaN) }
    }

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
