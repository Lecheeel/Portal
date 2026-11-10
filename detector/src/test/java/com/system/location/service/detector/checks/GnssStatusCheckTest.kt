package com.system.location.service.detector.checks

import com.system.location.service.detector.model.GnssFrameSnapshot
import com.system.location.service.detector.model.GnssSatelliteSnapshot
import com.system.location.service.detector.model.GnssReference
import com.system.location.service.detector.model.GnssEvidenceSnapshot
import com.system.location.service.detector.model.SampleSnapshot
import org.junit.Assert.*
import org.junit.Test

class GnssStatusCheckTest {
    private val check = GnssStatusCheck()
    private fun satellite(id: Int, used: Boolean = true) = GnssSatelliteSnapshot(
        1, id, 20f + id % 20, 10f + id % 70, id * 17f % 360, used, true, true, false)
    private fun analyze(satellites: List<GnssSatelliteSnapshot>) = check.analyze(
        (0..2).map { GnssFrameSnapshot(1000L + it * 1000, satellites) })

    @Test fun referenceComparisonIsVisibleAndDescriptiveWithoutAnInventedForgeryThreshold() {
        val frames = (0L..30L).map { GnssFrameSnapshot(it * 1000, listOf(satellite(1), satellite(2))) }
        val reference = GnssReference("device", "室外", 40_000, GnssEvidenceSnapshot(0, 30_000, frames))
        val snapshot = SampleSnapshot(0, 30_000, gnssFrames = frames.map { f ->
            f.copy(satellites = f.satellites.map { it.copy(constellation = 6) })
        })
        val lines = check.profileEvidence(snapshot, reference, "")
        assertTrue(lines.any { it.contains("星座=1.000") })
        assertTrue(lines.any { it.contains("GPS=1.000") })
        assertTrue(lines.any { it.contains("GALILEO=1.000") })
        assertTrue(lines.any { it.contains("不设伪造阈值") })
        assertFalse(lines.any { it.contains("覆盖不足") })
        assertTrue(check.profileEvidence(snapshot.copy(gnssFrames = frames.take(3)), reference, "")
            .any { it.contains("覆盖不足") })
        assertEquals(listOf("画像比较：文件损坏"), check.profileEvidence(snapshot, null, "文件损坏"))
        assertTrue(check.profileEvidence(snapshot.copy(gnssFrames = emptyList()), reference, "")
            .single().startsWith("画像比较不可用"))
    }

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
        val result = analyze(dualBand)
        assertTrue(result.issues.isEmpty())
        assertEquals(listOf(40, 40, 40), result.satellitesPerFrame)
        assertEquals(listOf(40, 40, 40), result.usedInFixPerFrame)
        assertEquals(mapOf(1 to 120), result.constellationHistogram)
        assertEquals(120, result.satelliteSamples)
        assertEquals(240, result.cn0Samples)
        val oneIdentity = analyze(List(8) { satellite(1) })
        assertTrue(oneIdentity.observations.any { it.contains("仅 1 颗独立卫星") })
    }

    @Test fun constellationRatiosCountIdentitiesAndAnyUsedSignalMarksItsSatelliteInUse() {
        val signals = listOf(satellite(1, false), satellite(1, true), satellite(2, false).copy(constellation = 6))
        val result = analyze(signals)
        assertEquals(mapOf(1 to 3, 6 to 3), result.constellationHistogram)
        assertEquals(listOf(2, 2, 2), result.satellitesPerFrame)
        assertEquals(listOf(1, 1, 1), result.usedInFixPerFrame)
        assertEquals(3, result.usedInFixTotal)
    }

    @Test fun identityTurnoverRemainsDescriptiveWithoutAttributingItToRandomization() {
        val result = check.analyze((0..2).map { index ->
            GnssFrameSnapshot(1000L + index * 1000,
                (1..8).map { satellite(it + index * 8, false).copy(cn0DbHz = 0f) })
        })
        assertEquals(listOf(0, 0, 0), result.satellitesPerFrame)
        assertNull(result.jaccardMin)
        assertNull(result.jaccardAvg)
        assertEquals(0, result.jaccardPairs)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.observations.any { it.contains("没有可用信号") })
        assertFalse(result.observations.any { it.contains("shuffle") || it.contains("Jaccard") })
    }
}
