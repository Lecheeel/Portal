package com.system.location.service.core.gnss

import kotlin.math.*

/** ECEF at GPS transmission time. clockBiasSeconds includes relativity, excludes signal TGD. */
data class GpsSatelliteState(val xMeters: Double, val yMeters: Double, val zMeters: Double,
    val clockBiasSeconds: Double)

data class GpsPropagationResult(val state: GpsSatelliteState?, val reason: String? = null)

/**
 * IS-GPS-200N Table 20-IV and 20.3.3.3.3.1. GPS LNAV only; broadcast harmonic parameters
 * already describe Earth-fixed coordinates. Never rotate these through a generic ECI orbit.
 * Input is full GPS TRANSMISSION time, after solving propagation delay/receiver-clock timing.
 * This component does not claim to decode raw messages or produce a complete observable frame.
 * https://archive.gps.gov/technical/icwg/IS-GPS-200N.pdf
 */
object GpsBroadcastPropagator {
    const val EARTH_MU = 3.986005e14
    const val EARTH_ROTATION_RAD_PER_SECOND = 7.2921151467e-5
    private const val RELATIVITY_F = -4.442807633e-10

    fun propagate(ephemeris: GpsLnavEphemeris, transmissionTime: GpsTime): GpsPropagationResult {
        val e = ephemeris
        // Check absolute fit validity BEFORE any periodic reduction; stale weeks cannot alias now.
        if (transmissionTime.secondsSince(e.validFrom) < 0 || e.validUntil.secondsSince(transmissionTime) < 0)
            return GpsPropagationResult(null, "outside absolute GPS ephemeris fit interval")
        if (e.health != 0) return GpsPropagationResult(null, "GPS satellite is not healthy")
        if (e.toc.secondsSince(e.validFrom) < 0 || e.validUntil.secondsSince(e.toc) < 0)
            return GpsPropagationResult(null, "clock reference outside validated fit interval")
        val tk = transmissionTime.secondsSince(e.toe)
        val axis = e.sqrtSemiMajorAxis * e.sqrtSemiMajorAxis
        val meanMotion = sqrt(EARTH_MU / (axis * axis * axis)) + e.deltaMeanMotionRadPerSecond
        if (!meanMotion.isFinite() || meanMotion <= 0) return GpsPropagationResult(null, "invalid GPS mean motion")
        val meanAnomaly = (e.meanAnomalyRad + meanMotion * tk) % (2 * PI)
        var eccentricAnomaly = meanAnomaly
        var converged = false
        for (iteration in 1..20) {
            val correction = (eccentricAnomaly - e.eccentricity * sin(eccentricAnomaly) - meanAnomaly) /
                (1 - e.eccentricity * cos(eccentricAnomaly))
            eccentricAnomaly -= correction
            if (iteration >= 3 && abs(correction) <= 1e-14) { converged = true; break }
        }
        if (!converged || !eccentricAnomaly.isFinite()) return GpsPropagationResult(null, "Kepler equation did not converge")
        val trueAnomaly = atan2(sqrt(1 - e.eccentricity * e.eccentricity) * sin(eccentricAnomaly),
            cos(eccentricAnomaly) - e.eccentricity)
        val phi = trueAnomaly + e.argumentOfPerigeeRad
        val sin2Phi = sin(2 * phi)
        val cos2Phi = cos(2 * phi)
        val u = phi + e.cusRad * sin2Phi + e.cucRad * cos2Phi
        val radius = axis * (1 - e.eccentricity * cos(eccentricAnomaly)) + e.crsMeters * sin2Phi + e.crcMeters * cos2Phi
        val inclination = e.inclinationRad + e.inclinationRateRadPerSecond * tk + e.cisRad * sin2Phi + e.cicRad * cos2Phi
        val node = e.ascendingNodeRad + (e.ascendingNodeRateRadPerSecond - EARTH_ROTATION_RAD_PER_SECOND) * tk -
            EARTH_ROTATION_RAD_PER_SECOND * e.toe.secondsOfWeek
        val orbitalX = radius * cos(u)
        val orbitalY = radius * sin(u)
        val dtClock = transmissionTime.secondsSince(e.toc)
        val clockBias = e.af0Seconds + e.af1SecondsPerSecond * dtClock + e.af2SecondsPerSecondSquared * dtClock * dtClock +
            RELATIVITY_F * e.eccentricity * e.sqrtSemiMajorAxis * sin(eccentricAnomaly)
        val state = GpsSatelliteState(
            orbitalX * cos(node) - orbitalY * cos(inclination) * sin(node),
            orbitalX * sin(node) + orbitalY * cos(inclination) * cos(node),
            orbitalY * sin(inclination), clockBias,
        )
        if (!listOf(state.xMeters, state.yMeters, state.zMeters, state.clockBiasSeconds).all(Double::isFinite) || radius <= 0)
            return GpsPropagationResult(null, "non-finite or invalid GPS propagation result")
        return GpsPropagationResult(state)
    }
}
