package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssVirtualSkySupplementTest {
    private val request = GnssSkyRequest(1_800_000_000_000, Wgs84(31.0, 121.0), 10.0)
    private val identities = (1..32).map { Constellation.GPS to it } + (1..36).map { Constellation.GALILEO to it }
    private val supplement = GnssVirtualSkySupplement(identities, GnssOutputLimits(64, 36, 32), 123)
    private val provenance = GnssOrbitProvenance("real-fixture", "1", "test", 1000, GnssOrbitSourceKind.ALMANAC)
    private val existing = GnssOrbitPosition(Constellation.GPS, 1, EcefPosition(26_560_000.0, 0.0, 0.0),
        provenance, false, true)

    @Test fun fillsVisibleFloorWithoutReusingVisibleOrHiddenOrbitalIdentity() {
        val reserved = setOf(Constellation.GPS to 1, Constellation.GPS to 2)
        val result = supplement.supplement(request, listOf(existing), reserved, 32)
        assertEquals(31, result.positions.size)
        assertTrue(result.positions.none { (it.constellation to it.svid) in reserved })
        assertTrue(result.positions.all { it.provenance.kind == GnssOrbitSourceKind.VIRTUAL })
        assertTrue(result.positions.all { GnssObserverGeometry.lineOfSight(request.observer, 10.0, it.position)!!.elevationDegrees in 14.99..80.01 })
        assertEquals(32, (result.positions + existing).map { it.constellation to it.svid }.distinct().size)
    }

    @Test fun unsupportedCapacityReportsFailureWithoutPartialPretendGuarantee() {
        val result = supplement.supplement(request, listOf(existing), emptySet(), 65)
        assertEquals(64, result.availableCapacity)
        assertTrue(result.positions.isEmpty())
        assertTrue(result.diagnostics.single().contains("无法保证"))
        assertTrue(supplement.supplement(request, listOf(existing), emptySet(), 0).positions.isEmpty())
    }

    @Test fun identityTrajectoryDoesNotDependOnRequestedFloorAndRemainsContinuous() {
        val smaller = supplement.supplement(request, emptyList(), emptySet(), 10).positions
        val larger = supplement.supplement(request, emptyList(), emptySet(), 32).positions
        assertEquals(smaller, larger.take(10))
        val later = supplement.supplement(request.copy(atMillis = request.atMillis + 1000),
            emptyList(), emptySet(), 10).positions
        smaller.zip(later).forEach { (a, b) ->
            assertEquals(b.position, requireNotNull(a.simulatedNavigation).positionAt(request.copy(atMillis = request.atMillis + 1000)))
            assertEquals(a.constellation to a.svid, b.constellation to b.svid)
            val first = GnssObserverGeometry.lineOfSight(request.observer, 10.0, a.position)!!
            val second = GnssObserverGeometry.lineOfSight(request.observer, 10.0, b.position)!!
            assertTrue(kotlin.math.abs(first.elevationDegrees - second.elevationDegrees) < 0.1)
        }
    }

    @Test fun gpsAndSbasShareOneNmeaTalkerBudget() {
        val combined = GnssVirtualSkySupplement((1..32).map { Constellation.GPS to it } +
            (120..151).map { Constellation.SBAS to it }, GnssOutputLimits(64, 36, 32), 0)
        val result = combined.supplement(request, emptyList(), emptySet(), 37)
        assertEquals(36, result.availableCapacity)
        assertTrue(result.positions.isEmpty())
    }
}
