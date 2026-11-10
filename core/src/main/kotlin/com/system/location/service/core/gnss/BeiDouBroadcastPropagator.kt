package com.system.location.service.core.gnss

import kotlin.math.*

/** BDCS ECEF at transmission BDT; polynomial plus relativity clock without signal TGD. */
data class BeiDouSatelliteState(val xMeters: Double, val yMeters: Double, val zMeters: Double,
    val clockBiasSeconds: Double)
data class BeiDouPropagationResult(val state: BeiDouSatelliteState?, val reason: String? = null)

/**
 * BDS-SIS-ICD-B1I-3.0 §§5.2.4.9/12, Table 5-11. Mathematical propagation only.
 * GEO uses the specified user-defined inertial coordinates, Rx(-5 degrees), then Rz(Omega_e*tk).
 * MEO/IGSO use the broadcast BDCS ascending-node equation directly; no extra ECI rotation.
 * https://en.beidou.gov.cn/SYSTEMS/ICD/201902/P020190227702348791891.pdf
 */
object BeiDouBroadcastPropagator {
    const val EARTH_MU = 3.986004418e14
    const val EARTH_ROTATION_RAD_PER_SECOND = 7.2921150e-5
    private const val SPEED_OF_LIGHT = 299792458.0

    fun propagate(e: BeiDouBroadcastEphemeris, transmission: BeiDouTime): BeiDouPropagationResult {
        fun invalid(reason: String) = BeiDouPropagationResult(null, reason)
        if (transmission.secondsSince(e.validFrom) < 0 || e.validUntil.secondsSince(transmission) < 0)
            return invalid("outside absolute BeiDou validity interval")
        val tk = transmission.secondsSince(e.toe)
        val a = e.sqrtSemiMajorAxis * e.sqrtSemiMajorAxis
        val n = sqrt(EARTH_MU / (a * a * a)) + e.deltaMeanMotionRadPerSecond
        if (!n.isFinite() || n <= 0) return invalid("invalid BeiDou mean motion")
        val mean = (e.meanAnomalyRad + n * tk) % (2 * PI)
        var eccentric = mean
        var converged = false
        for (iteration in 1..30) {
            val delta = (eccentric - e.eccentricity * sin(eccentric) - mean) /
                (1 - e.eccentricity * cos(eccentric))
            eccentric -= delta
            if (iteration >= 3 && abs(delta) <= 1e-14) { converged = true; break }
        }
        if (!converged || !eccentric.isFinite()) return invalid("BeiDou Kepler equation did not converge")
        val anomaly = atan2(sqrt(1 - e.eccentricity * e.eccentricity) * sin(eccentric), cos(eccentric) - e.eccentricity)
        val phi = anomaly + e.argumentOfPerigeeRad
        val sine = sin(2 * phi)
        val cosine = cos(2 * phi)
        val u = phi + e.cusRad * sine + e.cucRad * cosine
        val radius = a * (1 - e.eccentricity * cos(eccentric)) + e.crsMeters * sine + e.crcMeters * cosine
        val i = e.inclinationRad + e.inclinationRateRadPerSecond * tk + e.cisRad * sine + e.cicRad * cosine
        val nodeRate = e.ascendingNodeRateRadPerSecond -
            if (e.orbitType == BeiDouOrbitType.GEO) 0.0 else EARTH_ROTATION_RAD_PER_SECOND
        val node = e.ascendingNodeRad + nodeRate * tk - EARTH_ROTATION_RAD_PER_SECOND * e.toe.secondsOfWeek
        val x = radius * cos(u)
        val y = radius * sin(u)
        var px = x * cos(node) - y * cos(i) * sin(node)
        var py = x * sin(node) + y * cos(i) * cos(node)
        var pz = y * sin(i)
        if (e.orbitType == BeiDouOrbitType.GEO) {
            // ICD matrices use passive rotations: Rx(phi)[y,z]=[cos* y+sin* z,-sin* y+cos* z].
            val tilt = Math.toRadians(-5.0)
            val tiltedY = cos(tilt) * py + sin(tilt) * pz
            val tiltedZ = -sin(tilt) * py + cos(tilt) * pz
            val angle = EARTH_ROTATION_RAD_PER_SECOND * tk
            py = -sin(angle) * px + cos(angle) * tiltedY
            px = cos(angle) * px + sin(angle) * tiltedY
            pz = tiltedZ
        }
        val dt = transmission.secondsSince(e.toc)
        val relativity = -2 * sqrt(EARTH_MU) / (SPEED_OF_LIGHT * SPEED_OF_LIGHT) *
            e.eccentricity * e.sqrtSemiMajorAxis * sin(eccentric)
        val clock = e.af0Seconds + e.af1SecondsPerSecond * dt + e.af2SecondsPerSecondSquared * dt * dt + relativity
        if (radius <= 0 || !listOf(px, py, pz, clock).all(Double::isFinite))
            return invalid("invalid BeiDou propagation result")
        return BeiDouPropagationResult(BeiDouSatelliteState(px, py, pz, clock))
    }
}
