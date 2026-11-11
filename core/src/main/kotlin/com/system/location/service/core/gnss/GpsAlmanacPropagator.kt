package com.system.location.service.core.gnss

import kotlin.math.*

data class GpsAlmanacPositionResult(val position: EcefPosition?, val reason: String? = null)

/**
 * IS-GPS-200N 20.3.3.5.2.1 / Table 20-IV with omitted correction terms zero.
 * Yuma inclination is already absolute. Output is approximate ECEF sky geometry at the
 * supplied GPS epoch, not a measured signal or precision reception-time observable.
 * The caller chooses a finite age budget for its application; this is not an accuracy warranty
 * or a universal normative almanac lifetime. Absolute age is checked before angle reduction.
 * https://archive.gps.gov/technical/icwg/IS-GPS-200N.pdf
 */
object GpsAlmanacPropagator {
    fun position(almanac: GpsYumaAlmanac, time: GpsTime,
        maximumEpochDistanceSeconds: Double): GpsAlmanacPositionResult {
        require(maximumEpochDistanceSeconds.isFinite() && maximumEpochDistanceSeconds >= 0)
        val age = time.secondsSince(almanac.referenceTime)
        if (abs(age) > maximumEpochDistanceSeconds)
            return GpsAlmanacPositionResult(null, "outside configured GPS almanac age budget")
        if (almanac.health != 0)
            return GpsAlmanacPositionResult(null, "GPS almanac satellite is not healthy")
        val axis = almanac.sqrtSemiMajorAxis * almanac.sqrtSemiMajorAxis
        val meanMotion = sqrt(GpsBroadcastPropagator.EARTH_MU / (axis * axis * axis))
        val meanAnomaly = (almanac.meanAnomalyRad + meanMotion * age) % (2 * PI)
        var eccentricAnomaly = meanAnomaly
        var converged = false
        for (iteration in 1..20) {
            val step = (eccentricAnomaly - almanac.eccentricity * sin(eccentricAnomaly) - meanAnomaly) /
                (1 - almanac.eccentricity * cos(eccentricAnomaly))
            eccentricAnomaly -= step
            if (abs(step) <= 1e-14) { converged = true; break }
        }
        if (!converged || !eccentricAnomaly.isFinite())
            return GpsAlmanacPositionResult(null, "GPS almanac Kepler equation did not converge")
        // Orbital-plane Cartesian coordinates avoid a second angular conversion near perigee.
        val x = axis * (cos(eccentricAnomaly) - almanac.eccentricity)
        val y = axis * sqrt(1 - almanac.eccentricity * almanac.eccentricity) * sin(eccentricAnomaly)
        val argument = almanac.argumentOfPerigeeRad
        val orbitalX = x * cos(argument) - y * sin(argument)
        val orbitalY = x * sin(argument) + y * cos(argument)
        val rotation = GpsBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND
        val node = almanac.ascendingNodeRad + (almanac.ascendingNodeRateRadPerSecond - rotation) * age -
            rotation * almanac.referenceTime.secondsOfWeek
        val inclination = almanac.inclinationRad
        val output = listOf(
            orbitalX * cos(node) - orbitalY * cos(inclination) * sin(node),
            orbitalX * sin(node) + orbitalY * cos(inclination) * cos(node),
            orbitalY * sin(inclination),
        )
        if (!output.all(Double::isFinite))
            return GpsAlmanacPositionResult(null, "non-finite GPS almanac position")
        return GpsAlmanacPositionResult(EcefPosition(output[0], output[1], output[2]))
    }
}
