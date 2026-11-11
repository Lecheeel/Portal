package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GnssSimulationPolicyTest {
    private val request = GnssSkyRequest(1_800_000_000_000, Wgs84(30.0, 120.0), 10.0)

    @Test fun missingPolicyFieldsSelectAutonomousCapableDefaults() {
        val policy = Json.decodeFromString<GnssSimulationPolicy>("{}")
        assertEquals(GnssSimulationMode.AUTO, policy.mode)
        assertEquals(GnssMinimumPolicy.PHYSICAL_FIRST, policy.minimumPolicy)
        assertEquals(GnssSimulationEnvironment.OPEN_SKY, policy.environment)
        val explicit = policy.copy(mode = GnssSimulationMode.AUTONOMOUS,
            minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY,
            environment = GnssSimulationEnvironment.INDOOR, virtualSeed = Long.MIN_VALUE)
        assertEquals(explicit, Json.decodeFromString<GnssSimulationPolicy>(
            Json.encodeToString(GnssSimulationPolicy.serializer(), explicit)))
    }

    @Test fun selectsBestApplicableSourcePerIdentityWithoutGlobalExpiry() {
        val providers = listOf(
            provider(GnssOrbitSourceKind.VIRTUAL, 1, 2, 3),
            provider(GnssOrbitSourceKind.ALMANAC, 1, 2),
            provider(GnssOrbitSourceKind.BROADCAST_EPHEMERIS, 1),
        )
        val selected = GnssOrbitResolver(providers).positions(request).positions.associateBy { it.svid }
        assertEquals(3, selected.size)
        assertEquals(GnssOrbitSourceKind.BROADCAST_EPHEMERIS, selected.getValue(1).provenance.kind)
        assertEquals(GnssOrbitSourceKind.ALMANAC, selected.getValue(2).provenance.kind)
        assertEquals(GnssOrbitSourceKind.VIRTUAL, selected.getValue(3).provenance.kind)
        // Selection must preserve the resource epoch instead of restamping at request time.
        assertEquals(100L, selected.getValue(2).provenance.referenceEpochMillis)
    }

    @Test fun emptyExpiredSourceDoesNotBlockLowerPriorityDataAndKeepsReason() {
        val expired = object : GnssOrbitProvider {
            override val kind = GnssOrbitSourceKind.BROADCAST_EPHEMERIS
            override fun positions(request: GnssSkyRequest) =
                GnssOrbitBatch(emptyList(), listOf("GPS:1 expired"))
        }
        val result = GnssOrbitResolver(listOf(expired, provider(GnssOrbitSourceKind.ALMANAC, 1)))
            .positions(request)
        assertEquals(GnssOrbitSourceKind.ALMANAC, result.positions.single().provenance.kind)
        assertEquals(listOf("GPS:1 expired"), result.diagnostics)
    }

    @Test fun resolverPassesTargetTimeAndLocationToEveryAdapter() {
        val requests = mutableListOf<GnssSkyRequest>()
        val recording = object : GnssOrbitProvider {
            override val kind = GnssOrbitSourceKind.ALMANAC
            override fun positions(request: GnssSkyRequest): GnssOrbitBatch {
                requests += request
                return GnssOrbitBatch(emptyList())
            }
        }
        val resolver = GnssOrbitResolver(listOf(recording))
        resolver.positions(request)
        val other = request.copy(atMillis = request.atMillis + 1000, observer = Wgs84(-30.0, -60.0))
        resolver.positions(other)
        assertEquals(listOf(request, other), requests)
    }

    private fun provider(sourceKind: GnssOrbitSourceKind, vararg svids: Int) = object : GnssOrbitProvider {
        override val kind = sourceKind
        override fun positions(request: GnssSkyRequest) = GnssOrbitBatch(svids.map {
            GnssOrbitPosition(Constellation.GPS, it, EcefPosition(26_560_000.0, 0.0, 0.0),
                GnssOrbitProvenance("fixture", "1", "test", 100, kind),
                hasEphemeris = kind == GnssOrbitSourceKind.BROADCAST_EPHEMERIS,
                hasAlmanac = kind == GnssOrbitSourceKind.ALMANAC)
        })
    }
}
