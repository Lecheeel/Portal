package com.system.location.service.core.gnss

import kotlin.math.*

/** Position and ellipsoid-normal orientation in BDCS at reception. No WGS84 conversion is implied. */
data class BdcsReceiver(val position: EcefPosition, val orientation: GeodeticOrientation)

data class BeiDouReceptionObservation(
    val transmissionTime: BeiDouTime,
    val propagationSeconds: Double,
    /** Transmission position rotated into the BDCS ECEF axes at reception. */
    val satelliteInReceptionEcef: EcefPosition,
    val lineOfSight: GnssLineOfSight,
    /** Polynomial plus relativity for the B1I/B2I clock model; no single-frequency TGD correction. */
    val clockBiasSeconds: Double,
)

data class BeiDouReceptionResult(val observation: BeiDouReceptionObservation?, val reason: String? = null)

/**
 * Geometric vacuum light-time using the B1I 3.0 Table 5-11 BDCS transmission position.
 * Reception and transmission epochs are BDT, not receiver/satellite clock readings.
 * The ECEF rotation is Rz(-Omega_e * flightTime), into reception axes; it is applied once.
 * This mathematical geometry does not establish signal usability, atmospheric corrections,
 * pseudorange, coordinate-frame transformation, or the provenance of the validity interval.
 */
object BeiDouReceptionGeometry {
    const val SPEED_OF_LIGHT_METERS_PER_SECOND = 299792458.0

    fun observe(e: BeiDouBroadcastEphemeris, receptionTime: BeiDouTime,
        receiver: BdcsReceiver): BeiDouReceptionResult {
        fun invalid(reason: String) = BeiDouReceptionResult(null, reason)
        // Bound orbital radius by a(1+e) and the two radial harmonic amplitudes.
        val maximumRadius = e.sqrtSemiMajorAxis * e.sqrtSemiMajorAxis * (1 + e.eccentricity) +
            abs(e.crsMeters) + abs(e.crcMeters)
        val r = receiver.position
        val maximumDelay = (maximumRadius + hypot(hypot(r.xMeters, r.yMeters), r.zMeters)) /
            SPEED_OF_LIGHT_METERS_PER_SECOND
        if (!maximumDelay.isFinite()) return invalid("non-finite BeiDou reception bound")
        var lower = max(0.0, receptionTime.secondsSince(e.validUntil))
        var upper = min(maximumDelay, receptionTime.secondsSince(e.validFrom))
        if (lower > upper) return invalid("no transmission inside absolute BeiDou validity interval")

        fun evaluate(delay: Double): BeiDouReceptionObservation? {
            val transmission = receptionTime.plusSeconds(-delay)
            val state = BeiDouBroadcastPropagator.propagate(e, transmission).state ?: return null
            val angle = BeiDouBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND * delay
            val position = EcefPosition(cos(angle) * state.xMeters + sin(angle) * state.yMeters,
                -sin(angle) * state.xMeters + cos(angle) * state.yMeters, state.zMeters)
            val los = GnssObserverGeometry.lineOfSight(r, receiver.orientation, position) ?: return null
            return BeiDouReceptionObservation(transmission, delay, position, los,
                state.clockBiasSeconds)
        }
        fun residual(o: BeiDouReceptionObservation) =
            o.lineOfSight.rangeMeters / SPEED_OF_LIGHT_METERS_PER_SECOND - o.propagationSeconds
        val atLower = evaluate(lower) ?: return invalid("unavailable BeiDou state at reception bracket")
        val atUpper = evaluate(upper) ?: return invalid("unavailable BeiDou state at reception bracket")
        if (residual(atLower) < -1e-13 || residual(atUpper) > 1e-13)
            return invalid("light-time solution outside absolute BeiDou validity interval")
        // All trials stay inside the explicit validity interval. Bisection has a finite budget.
        repeat(50) {
            val result = evaluate(lower + (upper - lower) / 2)
                ?: return invalid("BeiDou light-time evaluation failed")
            val error = residual(result)
            if (abs(error) <= 1e-13) return BeiDouReceptionResult(result)
            if (error > 0) lower = result.propagationSeconds else upper = result.propagationSeconds
        }
        return invalid("BeiDou light-time equation did not converge")
    }
}
