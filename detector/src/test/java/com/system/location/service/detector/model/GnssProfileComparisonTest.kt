package com.system.location.service.detector.model

import org.junit.Assert.*
import org.junit.Test

class GnssProfileComparisonTest {
    private fun s(id: Int, system: Int = 1, used: Boolean = false, cn0: Float = 30f) =
        GnssSatelliteSnapshot(system, id, cn0, 90f, 10f, used, true, true, false)
    private fun profile(signals: List<GnssSatelliteSnapshot>, times: List<Long> = (0L..30L).map { it * 1000 }) =
        GnssWindowProfile.from(GnssEvidenceSnapshot(0, times.maxOrNull() ?: 0,
            times.map { GnssFrameSnapshot(it, signals) }))

    @Test fun dualBandCountsOnceAndUsesAnySignalButJointDistributionCountsSignals() {
        val p = profile(listOf(s(1), s(1, used = true, cn0 = 50f), s(2, 6)))
        assertEquals(2.0, p.meanVisible, 0.0)
        assertEquals(0.5, p.usedFraction!!, 0.0)
        assertEquals(mapOf(1 to 31, 6 to 31), p.constellationCounts)
        assertEquals(93, p.signalBins.values.sum())
        assertTrue(p.adequate)
    }

    @Test fun analyticalTotalVariationAndRatioDeltas() {
        val a = profile(listOf(s(1), s(2, 6)))
        val b = profile(listOf(s(1), s(2), s(3), s(4)))
        val delta = GnssProfileComparison.compare(a, b)
        assertEquals(1.0, delta.visibleCountTotalVariation!!, 0.0)
        assertEquals(0.5, delta.constellationTotalVariation!!, 0.0)
        assertEquals(0.5, delta.signalJointTotalVariation!!, 0.0)
        assertEquals(2.0, delta.meanVisibleDelta, 0.0)
        assertEquals(mapOf(1 to 0.5, 6 to -0.5), delta.constellationRatioDeltas)
        assertEquals(0.0, GnssProfileComparison.compare(a, a).signalJointTotalVariation!!, 0.0)
    }

    @Test fun lastArrivalPerSecondPreventsCallbackBurstWeighting() {
        val evidence = GnssEvidenceSnapshot(0, 1000, listOf(
            GnssFrameSnapshot(0, listOf(s(1))), GnssFrameSnapshot(999, listOf(s(1), s(2))),
            GnssFrameSnapshot(1000, emptyList())))
        val p = GnssWindowProfile.from(evidence)
        assertEquals(mapOf(2 to 1, 0 to 1), p.visibleCounts)
        assertEquals(1.0, p.meanVisible, 0.0)
        assertFalse(p.adequate)
    }

    @Test fun emptySkyHasNoRatioAndLongGapsAreNotAdequateCoverage() {
        val empty = profile(emptyList())
        val diff = GnssProfileComparison.compare(empty, profile(listOf(s(1))))
        assertNull(diff.constellationTotalVariation)
        assertNull(diff.usedFractionDelta)
        assertNull(diff.constellationRatioDeltas)
        assertEquals(1.0, diff.visibleCountTotalVariation!!, 0.0)
        assertFalse(diff.adequateWindows)
        assertFalse(profile(listOf(s(1)), (0L..30L).map { it * 3000 }).adequate)
    }

    @Test fun invalidEvidenceAndClockRollbackAreRejectedInsteadOfFiltered() {
        val invalid = listOf(s(1).copy(cn0DbHz = Float.NaN), s(1).copy(elevationDegrees = -1f),
            s(1).copy(azimuthDegrees = 360f), s(0), s(1, 8))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { profile(listOf(it)) } }
        assertThrows(IllegalArgumentException::class.java) { profile(listOf(s(1)), listOf(1000, 0)) }
    }
}
