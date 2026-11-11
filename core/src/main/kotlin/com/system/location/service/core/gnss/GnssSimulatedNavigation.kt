package com.system.location.service.core.gnss

/** Ideal clock in a hypothetical navigation world; never decoded broadcast clock parameters. */
data class GnssVirtualClock(
    val referenceEpochMillis: Long,
    val offsetSeconds: Double = 0.0,
    val driftSecondsPerSecond: Double = 0.0,
) {
    init {
        require(referenceEpochMillis >= 0)
        require(offsetSeconds.isFinite() && driftSecondsPerSecond.isFinite())
    }

    fun offsetAt(atMillis: Long): Double = offsetSeconds +
        (atMillis.toDouble() - referenceEpochMillis.toDouble()) / 1000.0 * driftSecondsPerSecond
}

/**
 * A continuous trajectory and clock owned by a virtual source, not an instantaneous position.
 * The source defines its validity domain. Ideal deterministic worlds may cover all nonnegative
 * epochs; that says nothing about the validity of a real broadcast upload or an almanac.
 * A local quantity scenario may depend on the observer as part of its hypothetical world.
 */
class GnssSimulatedNavigation(
    val constellation: Constellation,
    val svid: Int,
    val provenance: GnssOrbitProvenance,
    val validFromMillis: Long,
    val validUntilMillis: Long,
    val clock: GnssVirtualClock,
    private val trajectory: (GnssSkyRequest) -> EcefPosition,
) {
    init {
        require(provenance.kind == GnssOrbitSourceKind.VIRTUAL)
        require(GnssSatelliteIds.nmeaId(constellation, svid) != null)
        require(validFromMillis >= 0 && validUntilMillis >= validFromMillis)
    }

    fun positionAt(request: GnssSkyRequest): EcefPosition? {
        if (request.atMillis !in validFromMillis..validUntilMillis || !clock.offsetAt(request.atMillis).isFinite())
            return null
        return trajectory(request)
    }

    fun matches(orbit: GnssOrbitPosition, request: GnssSkyRequest): Boolean =
        orbit.constellation == constellation && orbit.svid == svid && orbit.provenance == provenance &&
            positionAt(request) == orbit.position
}
