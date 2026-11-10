package com.system.location.service.core.gnss

import kotlin.math.*

/** GTRF ECEF at transmission GST; clock is the decoded dual-frequency clock, without BGD. */
data class GalileoSatelliteState(val xMeters: Double, val yMeters: Double, val zMeters: Double,
    val clockBiasSeconds: Double, val clockReference: GalileoClockReference)
data class GalileoPropagationResult(val state: GalileoSatelliteState?, val reason: String? = null)

/**
 * Galileo OS SIS ICD 2.2 Table 68, Eqs 14–16. Mathematical orbit/clock propagation only;
 * signal health/DVS/SISA, CRC assembly and signal usability are separate required checks.
 * This function neither converts GST to GPS/UTC nor implies a usable physical GNSS frame.
 * https://www.gsc-europa.eu/sites/default/files/sites/all/files/Galileo_OS_SIS_ICD_in_force.pdf
 */
object GalileoBroadcastPropagator {
    const val EARTH_MU = 3.986004418e14
    const val EARTH_ROTATION_RAD_PER_SECOND = 7.2921151467e-5
    private const val SPEED_OF_LIGHT = 299792458.0

    fun propagate(e: GalileoBroadcastEphemeris, transmission: GalileoTime): GalileoPropagationResult {
        fun invalid(reason: String) = GalileoPropagationResult(null, reason)
        if (transmission.secondsSince(e.validFrom) < 0 || e.validUntil.secondsSince(transmission) < 0)
            return invalid("outside absolute Galileo validity interval")
        val tk = transmission.secondsSince(e.toe)
        val a = e.sqrtSemiMajorAxis * e.sqrtSemiMajorAxis
        val n = sqrt(EARTH_MU / (a * a * a)) + e.deltaMeanMotionRadPerSecond
        if (!n.isFinite() || n <= 0) return invalid("invalid Galileo mean motion")
        val mean = (e.meanAnomalyRad + n * tk) % (2 * PI)
        var eccentric = mean
        var converged = false
        for (iteration in 1..30) {
            val delta = (eccentric - e.eccentricity * sin(eccentric) - mean) /
                (1 - e.eccentricity * cos(eccentric))
            eccentric -= delta
            if (iteration >= 3 && abs(delta) <= 1e-14) { converged = true; break }
        }
        if (!converged || !eccentric.isFinite()) return invalid("Galileo Kepler equation did not converge")
        val anomaly = atan2(sqrt(1 - e.eccentricity * e.eccentricity) * sin(eccentric),
            cos(eccentric) - e.eccentricity)
        val phi = anomaly + e.argumentOfPerigeeRad
        val sine = sin(2 * phi)
        val cosine = cos(2 * phi)
        val u = phi + e.cusRad * sine + e.cucRad * cosine
        val radius = a * (1 - e.eccentricity * cos(eccentric)) + e.crsMeters * sine + e.crcMeters * cosine
        val i = e.inclinationRad + e.inclinationRateRadPerSecond * tk + e.cisRad * sine + e.cicRad * cosine
        val node = e.ascendingNodeRad + (e.ascendingNodeRateRadPerSecond - EARTH_ROTATION_RAD_PER_SECOND) * tk -
            EARTH_ROTATION_RAD_PER_SECOND * e.toe.secondsOfWeek
        val x = radius * cos(u)
        val y = radius * sin(u)
        val dt = transmission.secondsSince(e.toc)
        // Compute Eq 16 from its defining constants, without borrowing GPS's relativity F.
        val relativity = -2 * sqrt(EARTH_MU) / (SPEED_OF_LIGHT * SPEED_OF_LIGHT) *
            e.eccentricity * e.sqrtSemiMajorAxis * sin(eccentric)
        val clock = e.af0Seconds + e.af1SecondsPerSecond * dt + e.af2SecondsPerSecondSquared * dt * dt + relativity
        val state = GalileoSatelliteState(x * cos(node) - y * cos(i) * sin(node),
            x * sin(node) + y * cos(i) * cos(node), y * sin(i), clock, e.clockReference)
        if (radius <= 0 || !listOf(state.xMeters, state.yMeters, state.zMeters, state.clockBiasSeconds).all(Double::isFinite))
            return invalid("non-finite or invalid Galileo propagation result")
        return GalileoPropagationResult(state)
    }
}
