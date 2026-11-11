package com.system.location.service.core.gnss

import java.util.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.*

/** Parameters of a hypothetical circular Walker-delta group, not an actual orbital upload. */
@Serializable
data class VirtualWalkerGroup(
    val constellation: Constellation,
    val svids: List<Int>,
    val planes: Int,
    val phaseFactor: Int,
    val orbitalRadiusMeters: Double,
    val inclinationRad: Double,
) {
    init {
        require(svids.isNotEmpty() && svids.distinct().size == svids.size)
        require(svids.all { GnssSatelliteIds.nmeaId(constellation, it) != null })
        require(planes in 1..svids.size && svids.size % planes == 0 && phaseFactor in 0..<planes)
        require(orbitalRadiusMeters in 6_500_000.0..60_000_000.0 && inclinationRad in 0.0..PI)
    }
}

@Serializable
data class VirtualWalkerResource(
    val formatVersion: Int = 1,
    val revision: String,
    val referenceEpochMillis: Long,
    /** Explicit orientation of this virtual world's inertial axes at its reference epoch. */
    val greenwichAngleRad: Double = 0.0,
    val groups: List<VirtualWalkerGroup>,
) {
    init {
        require(formatVersion == 1 && revision.isNotBlank() && referenceEpochMillis >= 0)
        require(greenwichAngleRad.isFinite() && groups.isNotEmpty())
        val identities = groups.flatMap { group -> group.svids.map { group.constellation to it } }
        require(identities.distinct().size == identities.size)
    }

    companion object {
        fun decode(text: String): VirtualWalkerResource {
            require(text.toByteArray(Charsets.UTF_8).size <= 65536)
            return Json.decodeFromString(serializer(), text)
        }
    }
}

/**
 * Persistent identities and deterministic two-body trajectories in an explicitly virtual world.
 * Group sizes and topology come from a resource, not a fixed production satellite catalog.
 * A finite constellation does not promise any requested visible lower bound.
 */
class VirtualWalkerOrbitProvider(resource: VirtualWalkerResource, seed: Long) : GnssOrbitProvider {
    override val kind = GnssOrbitSourceKind.VIRTUAL
    private val referenceEpochMillis = resource.referenceEpochMillis
    private val orientation = resource.greenwichAngleRad
    private val provenance = GnssOrbitProvenance("virtual-walker", resource.revision,
        "Portal hypothetical circular Walker-delta model", resource.referenceEpochMillis, kind)
    private data class Orbit(val constellation: Constellation, val svid: Int, val navigation: GnssSimulatedNavigation)
    private val orbits: List<Orbit>

    init {
        val random = Random(seed)
        orbits = resource.groups.flatMap { group ->
            val nodeOffset = random.nextDouble() * 2 * PI
            val anomalyOffset = random.nextDouble() * 2 * PI
            val perPlane = group.svids.size / group.planes
            group.svids.mapIndexed { index, svid ->
                val plane = index / perPlane
                val slot = index % perPlane
                val node = nodeOffset + 2 * PI * plane / group.planes
                val anomaly = anomalyOffset + 2 * PI * slot / perPlane +
                    2 * PI * group.phaseFactor * plane / group.svids.size
                val trajectory = KeplerOrbit(KeplerElements(
                    group.orbitalRadiusMeters, 0.0, group.inclinationRad, node, 0.0, anomaly, 0.0))
                val navigation = GnssSimulatedNavigation(group.constellation, svid, provenance,
                    0, Long.MAX_VALUE, GnssVirtualClock(referenceEpochMillis)) { request ->
                    val seconds = (request.atMillis.toDouble() - referenceEpochMillis.toDouble()) / 1000.0
                    val angle = (orientation + GpsBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND * seconds) % (2 * PI)
                    val inertial = trajectory.positionAt(seconds)
                    EcefPosition(cos(angle) * inertial.x + sin(angle) * inertial.y,
                        -sin(angle) * inertial.x + cos(angle) * inertial.y, inertial.z)
                }
                Orbit(group.constellation, svid, navigation)
            }
        }
    }

    override fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        val positions = orbits.map { orbit ->
            val ecef = requireNotNull(orbit.navigation.positionAt(request))
            GnssOrbitPosition(orbit.constellation, orbit.svid, ecef, provenance,
                hasEphemeris = false, hasAlmanac = false, simulatedNavigation = orbit.navigation)
        }
        return GnssOrbitBatch(positions, listOf("虚拟轨道天空：假想星座，不代表真实在轨观测"))
    }
}
