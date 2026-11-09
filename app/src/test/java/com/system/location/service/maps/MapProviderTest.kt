package com.system.location.service.maps

import org.junit.Assert.*
import org.junit.Test

class MapProviderTest {
    @Test fun existingAndUnknownPreferencesDefaultToAmap() {
        assertEquals(MapProvider.AMAP, MapProvider.restore(null))
        assertEquals(MapProvider.AMAP, MapProvider.restore("old"))
        assertEquals(MapProvider.TENCENT, MapProvider.restore("TENCENT"))
    }
    @Test fun tencentWalkingDeltaDecodesBothAxesWithoutMutatingInput() {
        val encoded = listOf(39.9, 116.4, 100.0, -200.0, -50.0, 300.0)
        val points = TencentPolyline.decode(encoded)
        assertEquals(39.9001, points[1].latitude, 1e-9)
        assertEquals(116.3998, points[1].longitude, 1e-9)
        assertEquals(39.90005, points[2].latitude, 1e-9)
        assertEquals(116.4001, points[2].longitude, 1e-9)
        assertEquals(100.0, encoded[2], 0.0)
    }
    @Test fun malformedWalkingResponseCannotBecomeSavedGeometry() {
        for (values in listOf(emptyList(), listOf(39.9, 116.4, 2.0), listOf(Double.NaN, 116.4, 2.0, 3.0))) {
            assertTrue(runCatching { TencentPolyline.decode(values) }.isFailure)
        }
    }
    @Test fun canonicalCoordinatesSurviveBothProviderBoundariesAndOutsideChina() {
        for (point in listOf(39.9 to 116.4, 22.54 to 114.06, 51.5 to -0.1)) {
            val stored = point.mapPoint.storedPoint
            assertEquals(point.first, stored.first, 0.000002)
            assertEquals(point.second, stored.second, 0.000002)
        }
    }
}
