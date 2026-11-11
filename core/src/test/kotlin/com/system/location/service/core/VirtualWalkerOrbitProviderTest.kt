package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class VirtualWalkerOrbitProviderTest {
    private val resource = VirtualWalkerResource(revision = "test-1", referenceEpochMillis = 1000,
        groups = listOf(VirtualWalkerGroup(Constellation.GPS, (1..24).toList(), 6, 1, 26_560_000.0, 0.95)))
    private val request = GnssSkyRequest(1_000_000, Wgs84(31.0, 121.0), 10.0)

    @Test fun resourceTopologyControlsCountAndAllIdentitiesRemainExplicitlyVirtual() {
        val batch = VirtualWalkerOrbitProvider(resource, 123).positions(request)
        assertEquals(24, batch.positions.size)
        assertTrue(batch.diagnostics.isNotEmpty())
        for (position in batch.positions) {
            assertEquals(GnssOrbitSourceKind.VIRTUAL, position.provenance.kind)
            assertFalse(position.hasEphemeris)
            assertFalse(position.hasAlmanac)
            val p = position.position
            assertEquals(26_560_000.0, sqrt(p.xMeters.pow(2) + p.yMeters.pow(2) + p.zMeters.pow(2)), 1e-6)
        }
        val smaller = resource.copy(groups = listOf(resource.groups.single().copy(svids = (1..12).toList())))
        assertEquals(12, VirtualWalkerOrbitProvider(smaller, 123).positions(request).positions.size)
    }

    @Test fun restartSameSeedReproducesSkyAndAdjacentEpochsPreserveIdentityAndContinuity() {
        val provider = VirtualWalkerOrbitProvider(resource, 123)
        val before = provider.positions(request).positions
        val restarted = VirtualWalkerOrbitProvider(resource, 123).positions(request).positions
        assertEquals(before.map { it.copy(simulatedNavigation = null) }, restarted.map { it.copy(simulatedNavigation = null) })
        assertNotEquals(before, VirtualWalkerOrbitProvider(resource, 124).positions(request).positions)
        val after = provider.positions(request.copy(atMillis = request.atMillis + 1000)).positions
        before.zip(after).forEach { (a, b) ->
            assertEquals(b.position, requireNotNull(a.simulatedNavigation).positionAt(request.copy(atMillis = request.atMillis + 1000)))
            assertEquals(0.0, a.simulatedNavigation!!.clock.offsetAt(request.atMillis), 0.0)
            assertEquals(a.constellation to a.svid, b.constellation to b.svid)
            val distance = sqrt((a.position.xMeters - b.position.xMeters).pow(2) +
                (a.position.yMeters - b.position.yMeters).pow(2) + (a.position.zMeters - b.position.zMeters).pow(2))
            assertTrue(distance in 1.0..6000.0)
        }
    }

    @Test fun targetProjectionChangesSkyAndFinitePoolDoesNotClaimArbitraryCapacity() {
        val positions = VirtualWalkerOrbitProvider(resource, 123).positions(request).positions
        fun visible(observer: Wgs84) = positions.filter {
            GnssObserverGeometry.lineOfSight(observer, 10.0, it.position)!!.aboveHorizon
        }.map { it.svid }.toSet()
        val local = visible(request.observer)
        val remote = visible(Wgs84(-31.0, -59.0))
        assertTrue(local.isNotEmpty() && remote.isNotEmpty())
        assertNotEquals(local, remote)
        assertTrue(local.size < positions.size)
    }

    @Test fun rejectsUnsupportedIdentitiesAndMalformedTopology() {
        assertThrows(IllegalArgumentException::class.java) { resource.groups.single().copy(svids = listOf(33)) }
        assertThrows(IllegalArgumentException::class.java) { resource.groups.single().copy(planes = 5) }
        assertThrows(IllegalArgumentException::class.java) { resource.copy(groups = resource.groups + resource.groups) }
    }
}
