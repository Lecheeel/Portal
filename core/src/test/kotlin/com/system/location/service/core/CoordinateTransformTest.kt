package com.system.location.service.core

import com.system.location.service.core.geo.*
import org.junit.Assert.*
import org.junit.Test

class CoordinateTransformTest {
    @Test fun domesticRoundTripAndOverseasIdentity() {
        val original = Wgs84(31.230456789, 121.473712345)
        val restored = CoordinateTransform.toWgs84(CoordinateTransform.toGcj02(original))
        assertEquals(original.latitude, restored.latitude, 1e-7)
        assertEquals(original.longitude, restored.longitude, 1e-7)
        val overseas = Wgs84(40.7, -74.0)
        assertEquals(overseas, CoordinateTransform.toWgs84(CoordinateTransform.toGcj02(overseas)))
    }
    @Test fun baiduConversionIsExplicit() {
        val gcj = Gcj02(31.23, 121.47)
        val restored = CoordinateTransform.toGcj02(CoordinateTransform.toBd09(gcj))
        assertEquals(gcj.latitude, restored.latitude, 2e-6)
        assertEquals(gcj.longitude, restored.longitude, 2e-6)
    }
}
