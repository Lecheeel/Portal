package com.system.location.service.detector.checks

import com.system.location.service.detector.model.GnssFrameSnapshot
import com.system.location.service.detector.model.GnssSatelliteSnapshot
import org.junit.Assert.*
import org.junit.Test

class GnssStatusCheckTest {
    private val check = GnssStatusCheck()
    private fun satellite(id: Int, used: Boolean = true) = GnssSatelliteSnapshot(
        1, id, 20f + id % 20, 10f + id % 70, id * 17f % 360, used, true, true, false)
    private fun analyze(satellites: List<GnssSatelliteSnapshot>) = check.analyze(
        (0..2).map { GnssFrameSnapshot(1000L + it * 1000, satellites) })

    @Test fun negativeElevationAndInvalidAnglesCannotBeDiscardedByCorrelationFiltering() {
        for (elevation in listOf(-1f, 91f, Float.NaN)) {
            assertTrue(analyze(listOf(satellite(1).copy(elevationDegrees = elevation))).issues.any { it.contains("仰角") })
        }
        for (azimuth in listOf(-1f, 360f, Float.POSITIVE_INFINITY)) {
            assertTrue(analyze(listOf(satellite(1).copy(azimuthDegrees = azimuth))).issues.any { it.contains("方位角") })
        }
    }

    @Test fun cn0UsesAndroidRangeIncluding63() {
        assertTrue(analyze(listOf(satellite(1).copy(cn0DbHz = 63f))).issues.isEmpty())
        for (cn0 in listOf(-1f, 63.01f, Float.NaN)) {
            assertTrue(analyze(listOf(satellite(1).copy(cn0DbHz = cn0))).issues.any { it.contains("CN0") })
        }
    }

    @Test fun sparseIndoorAndSingleSystemObservationsAreNotProofOfForgery() {
        val empty = analyze(emptyList())
        assertTrue(empty.issues.isEmpty())
        assertTrue(empty.observations.isNotEmpty())
        val unfixed = analyze((1..5).map { satellite(it, false) })
        assertTrue(unfixed.issues.isEmpty())
        assertTrue(unfixed.observations.any { it.contains("未参与定位") })
        val low = analyze((1..5).map { satellite(it, it <= 3) })
        assertTrue(low.issues.isEmpty())
        assertTrue(low.observations.any { it.contains("不足以确认三维定位") })
        val gpsOnly = analyze((1..8).map { satellite(it) })
        assertTrue(gpsOnly.issues.isEmpty())
        assertFalse(gpsOnly.observations.any { it.contains("星座数") })
    }

    @Test fun multibandCountsAbove64AndRepeatedSvidDoNotImplyInvalidStatus() {
        val dualBand = (1..40).flatMap { listOf(satellite(it), satellite(it).copy(cn0DbHz = 42f)) }
        assertEquals(80, dualBand.size)
        assertTrue(analyze(dualBand).issues.isEmpty())
        val oneIdentity = analyze(List(8) { satellite(1) })
        assertTrue(oneIdentity.observations.any { it.contains("仅 1 颗独立卫星") })
    }
}
