package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssSatelliteValidationTest {
    @Test fun fullAndroidSignalRangeSurvivesCaptureAndProfileHistograms() {
        for (cn0 in listOf(0f, 60f, 61f, 62f, 63f)) {
            val satellite = GnssSatellite(Constellation.GPS, 1, 45f, 120f, cn0, false, true, true)
            assertEquals(cn0, satellite.cn0DbHz, 0f)
            val profile = GnssObservationProfileBuilder.build(
                listOf(GnssObservationFrame(1000, satellites = listOf(satellite))), "receiver", "android")
            if (cn0 == 0f) {
                assertTrue("zero-CN0 placeholders must not form a signal profile", profile.constellations.isEmpty())
            } else {
                assertEquals(mapOf(cn0.toInt() to 1), profile.constellations.single().cn0Histogram)
            }
        }
        assertEquals(63f, GnssFrame.MAX_CN0_DB_HZ, 0f)
    }

    @Test fun valuesOutsideAndroidStatusRangeRemainInvalid() {
        for (cn0 in listOf(-0.1f, 63.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                GnssSatellite(Constellation.GPS, 1, 45f, 120f, cn0, false, true, true)
            }
        }
    }
}
