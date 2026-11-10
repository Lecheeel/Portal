package com.system.location.service.core.gnss

import kotlin.math.*

/** Position and ellipsoid-normal orientation in GTRF at reception. No WGS84 conversion is implied. */
data class GtrfReceiver(val position: EcefPosition, val orientation: GeodeticOrientation)

data class GalileoReceptionObservation(
    val transmissionTime: GalileoTime,
    val propagationSeconds: Double,
    /** Transmission position rotated into the GTRF ECEF axes at reception. */
    val satelliteInReceptionEcef: EcefPosition,
    val lineOfSight: GnssLineOfSight,
    /** Polynomial plus relativity for clockReference; no single-frequency BGD correction. */
    val clockBiasSeconds: Double,
    val clockReference: GalileoClockReference,
)

data class GalileoReceptionResult(val observation: GalileoReceptionObservation?, val reason: String? = null)

/**
 * Geometric vacuum light-time using the Galileo OS SIS ICD Table 68 transmission position.
 * Reception and transmission epochs are GST, not receiver/satellite clock readings.
 * The ECEF rotation is Rz(-Omega_e * flightTime), into reception axes; it is applied once.
 * This mathematical geometry does not establish signal usability, atmospheric corrections,
 * pseudorange, coordinate-frame transformation, or the provenance of the validity interval.
 */
object GalileoReceptionGeometry {
    const val SPEED_OF_LIGHT_METERS_PER_SECOND = 299792458.0

    fun observe(e: GalileoBroadcastEphemeris, receptionTime: GalileoTime,
        receiver: GtrfReceiver): GalileoReceptionResult {
        fun invalid(reason: String) = GalileoReceptionResult(null, reason)
        // Bound orbital radius by a(1+e) and the two radial harmonic amplitudes.
        val maximumRadius = e.sqrtSemiMajorAxis * e.sqrtSemiMajorAxis * (1 + e.eccentricity) +
            abs(e.crsMeters) + abs(e.crcMeters)
        val r = receiver.position
        val maximumDelay = (maximumRadius + hypot(hypot(r.xMeters, r.yMeters), r.zMeters)) /
            SPEED_OF_LIGHT_METERS_PER_SECOND
        if (!maximumDelay.isFinite()) return invalid("non-finite Galileo reception bound")
        var lower = max(0.0, receptionTime.secondsSince(e.validUntil))
        var upper = min(maximumDelay, receptionTime.secondsSince(e.validFrom))
        if (lower > upper) return invalid("no transmission inside absolute Galileo validity interval")

        fun evaluate(delay: Double): GalileoReceptionObservation? {
            val transmission = receptionTime.plusSeconds(-delay)
            val state = GalileoBroadcastPropagator.propagate(e, transmission).state ?: return null
            val angle = GalileoBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND * delay
            val position = EcefPosition(cos(angle) * state.xMeters + sin(angle) * state.yMeters,
                -sin(angle) * state.xMeters + cos(angle) * state.yMeters, state.zMeters)
            val los = GnssObserverGeometry.lineOfSight(r, receiver.orientation, position) ?: return null
            return GalileoReceptionObservation(transmission, delay, position, los,
                state.clockBiasSeconds, state.clockReference)
        }
        fun residual(o: GalileoReceptionObservation) =
            o.lineOfSight.rangeMeters / SPEED_OF_LIGHT_METERS_PER_SECOND - o.propagationSeconds
        val atLower = evaluate(lower) ?: return invalid("unavailable Galileo state at reception bracket")
        val atUpper = evaluate(upper) ?: return invalid("unavailable Galileo state at reception bracket")
        if (residual(atLower) < -1e-13 || residual(atUpper) > 1e-13)
            return invalid("light-time solution outside absolute Galileo validity interval")
        // All trials stay inside the explicit validity interval. Bisection has a finite budget.
        repeat(50) {
            val result = evaluate(lower + (upper - lower) / 2)
                ?: return invalid("Galileo light-time evaluation failed")
            val error = residual(result)
            if (abs(error) <= 1e-13) return GalileoReceptionResult(result)
            if (error > 0) lower = result.propagationSeconds else upper = result.propagationSeconds
        }
        return invalid("Galileo light-time equation did not converge")
    }
}
