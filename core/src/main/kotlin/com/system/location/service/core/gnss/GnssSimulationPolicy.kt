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
    val mode: GnssSimulationMode = GnssSimulationMode.AUTONOMOUS,
    val minimumPolicy: GnssMinimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY,
    val environment: GnssSimulationEnvironment = GnssSimulationEnvironment.OPEN_SKY,
    val virtualSeed: Long = 0,
) {
    companion object { const val DEFAULT_MINIMUM_SATELLITES = 59 }
}

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
    /** Signal selected by the adapter; a GLONASS slot does not itself establish an FCN. */
    val carrierHz: Float? = null,
    /** Only explicit virtual-world trajectory/clock knowledge can supply synthetic navigation. */
    val simulatedNavigation: GnssSimulatedNavigation? = null,
) {
    init {
        require(GnssSatelliteIds.nmeaId(constellation, svid) != null)
        require(carrierHz == null || (carrierHz.isFinite() && carrierHz > 0))
    }
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
    /** Applicable negative health/service evidence blocks lower-priority fallbacks. */
    val blockedIdentities: Set<Pair<Constellation, Int>> = emptySet(),
) {
    init {
        require(positions.map { it.constellation to it.svid }.distinct().size == positions.size)
        require(positions.all { (it.constellation to it.svid) in reservedIdentities })
        require(reservedIdentities.containsAll(blockedIdentities))
        require(positions.none { (it.constellation to it.svid) in blockedIdentities })
    }
}

/** Select per identity, so an invalid high-priority record cannot suppress a valid fallback. */
class GnssOrbitResolver(providers: List<GnssOrbitProvider>) {
    private val providers = providers.sortedBy { it.kind.ordinal }.toList()

    fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        val selected = linkedMapOf<Pair<Constellation, Int>, GnssOrbitPosition>()
        val diagnostics = mutableListOf<String>()
        val reserved = mutableSetOf<Pair<Constellation, Int>>()
        val blocked = mutableSetOf<Pair<Constellation, Int>>()
        for (provider in providers) {
            val batch = provider.positions(request)
            require(batch.positions.all { it.provenance.kind == provider.kind }) {
                "Orbit provider returned an inconsistent source kind"
            }
            diagnostics += batch.diagnostics
            reserved += batch.reservedIdentities
            for (position in batch.positions) {
                val identity = position.constellation to position.svid
                if (identity !in blocked) selected.putIfAbsent(identity, position)
            }
            blocked += batch.blockedIdentities
        }
        return GnssOrbitBatch(selected.values.toList(), diagnostics, reserved.toSet(), blocked - selected.keys)
    }
}
