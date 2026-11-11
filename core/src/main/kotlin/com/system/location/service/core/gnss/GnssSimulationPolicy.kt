package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable

/** Replay preserves the recording's sky; AUTO and AUTONOMOUS compute the target sky. */
@Serializable
enum class GnssSimulationMode { AUTO, REPLAY, AUTONOMOUS, OFF }

@Serializable
enum class GnssMinimumPolicy { PHYSICAL_FIRST, GUARANTEE_QUANTITY }

/** The simulated environment is independent of the handset's actual reception environment. */
@Serializable
enum class GnssSimulationEnvironment { OPEN_SKY, URBAN, INDOOR }

@Serializable
data class GnssSimulationPolicy(
    val mode: GnssSimulationMode = GnssSimulationMode.AUTO,
    val minimumPolicy: GnssMinimumPolicy = GnssMinimumPolicy.PHYSICAL_FIRST,
    val environment: GnssSimulationEnvironment = GnssSimulationEnvironment.OPEN_SKY,
    val virtualSeed: Long = 0,
)

enum class GnssOrbitSourceKind { BROADCAST_EPHEMERIS, ALMANAC, OFFLINE_ORBIT, VIRTUAL }

/** Resource identity describes its original epoch, never the last download attempt. */
data class GnssOrbitProvenance(
    val resourceId: String,
    val revision: String,
    val source: String,
    val referenceEpochMillis: Long,
    val kind: GnssOrbitSourceKind,
) {
    init {
        require(resourceId.isNotBlank() && revision.isNotBlank() && source.isNotBlank())
        require(referenceEpochMillis >= 0)
    }
}

data class GnssSkyRequest(
    val atMillis: Long,
    val observer: Wgs84,
    val altitudeMeters: Double,
) {
    init { require(atMillis >= 0 && altitudeMeters.isFinite()) }
}

/** No angles or measured receiver state: those are computed after orbital selection. */
data class GnssOrbitPosition(
    val constellation: Constellation,
    val svid: Int,
    val position: EcefPosition,
    val provenance: GnssOrbitProvenance,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
) {
    init { require(GnssSatelliteIds.nmeaId(constellation, svid) != null) }
}

/**
 * Each adapter owns its system-specific time, health and age checks. Unusable records must
 * be omitted with diagnostics, permitting other sources to supply the same identity.
 * Positions use WGS84-compatible ECEF axes at the requested epoch; adapters must explicitly
 * transform their native frame. This contract is approximate sky geometry, not pseudorange.
 */
interface GnssOrbitProvider {
    val kind: GnssOrbitSourceKind
    fun positions(request: GnssSkyRequest): GnssOrbitBatch
}

data class GnssOrbitBatch(
    val positions: List<GnssOrbitPosition>,
    val diagnostics: List<String> = emptyList(),
    /** Includes known unhealthy/stale identities omitted from positions; never reuse as additions. */
    val reservedIdentities: Set<Pair<Constellation, Int>> = positions.map { it.constellation to it.svid }.toSet(),
) {
    init {
        require(positions.map { it.constellation to it.svid }.distinct().size == positions.size)
        require(positions.all { (it.constellation to it.svid) in reservedIdentities })
    }
}

/** Select per identity, so an invalid high-priority record cannot suppress a valid fallback. */
class GnssOrbitResolver(providers: List<GnssOrbitProvider>) {
    private val providers = providers.sortedBy { it.kind.ordinal }.toList()

    fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        val selected = linkedMapOf<Pair<Constellation, Int>, GnssOrbitPosition>()
        val diagnostics = mutableListOf<String>()
        val reserved = mutableSetOf<Pair<Constellation, Int>>()
        for (provider in providers) {
            val batch = provider.positions(request)
            require(batch.positions.all { it.provenance.kind == provider.kind }) {
                "Orbit provider returned an inconsistent source kind"
            }
            diagnostics += batch.diagnostics
            reserved += batch.reservedIdentities
            for (position in batch.positions) {
                selected.putIfAbsent(position.constellation to position.svid, position)
            }
        }
        return GnssOrbitBatch(selected.values.toList(), diagnostics, reserved.toSet())
    }
}
