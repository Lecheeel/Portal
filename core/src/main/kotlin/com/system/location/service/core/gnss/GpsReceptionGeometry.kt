package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlin.math.*

data class GpsReceptionObservation(
    val transmissionTime: GpsTime,
    val propagationSeconds: Double,
    /** Satellite at transmission, rotated into the ECEF axes at reception. */
    val satelliteInReceptionEcef: EcefPosition,
    val lineOfSight: GnssLineOfSight,
    /** Polynomial plus relativity, without any signal-dependent group delay. */
    val clockBiasSeconds: Double,
    val l1CaClockBiasSeconds: Double,
)

data class GpsReceptionResult(val observation: GpsReceptionObservation?, val reason: String? = null)

/**
 * Geometric vacuum light-time, IS-GPS-200N 20.3.3.4.3.3/4.3.4. Both epochs are GPS
 * SYSTEM time; receiver/satellite clock readings are not accepted as these epochs.
 * This is not a pseudorange/phase model: atmosphere, antenna and receiver biases are absent.
 */
object GpsReceptionGeometry {
    const val SPEED_OF_LIGHT_METERS_PER_SECOND = 299792458.0

    fun observe(ephemeris: GpsLnavEphemeris, receptionTime: GpsTime,
        observer: Wgs84, ellipsoidHeightMeters: Double): GpsReceptionResult {
        fun invalid(reason: String) = GpsReceptionResult(null, reason)
        val receiver = GnssObserverGeometry.ecef(observer, ellipsoidHeightMeters)
        val e = ephemeris
        // A physical upper bound on path length, not an assumed 70/75ms flight time.
        val maximumRadius = e.sqrtSemiMajorAxis * e.sqrtSemiMajorAxis * (1 + e.eccentricity) +
            abs(e.crsMeters) + abs(e.crcMeters)
        val maximumDelay = (maximumRadius + hypot(hypot(receiver.xMeters, receiver.yMeters), receiver.zMeters)) /
            SPEED_OF_LIGHT_METERS_PER_SECOND
        if (!maximumDelay.isFinite()) return invalid("non-finite reception geometry bound")
        var lower = max(0.0, receptionTime.secondsSince(e.validUntil))
        var upper = min(maximumDelay, receptionTime.secondsSince(e.validFrom))
        if (lower > upper) return invalid("no possible transmission inside absolute GPS fit interval")

        fun evaluate(delay: Double): GpsReceptionObservation? {
            val transmission = receptionTime.plusSeconds(-delay)
            val state = GpsBroadcastPropagator.propagate(e, transmission).state ?: return null
            val angle = GpsBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND * delay
            // Choose the coincident ECI frame at reception: theta=(tT-tR)*Omega_e.
            val position = EcefPosition(cos(angle) * state.xMeters + sin(angle) * state.yMeters,
                -sin(angle) * state.xMeters + cos(angle) * state.yMeters, state.zMeters)
            val los = GnssObserverGeometry.lineOfSight(observer, ellipsoidHeightMeters, position) ?: return null
            return GpsReceptionObservation(transmission, delay, position, los,
                state.clockBiasSeconds, state.clockBiasSeconds - e.tgdSeconds)
        }
        fun residual(observation: GpsReceptionObservation) =
            observation.lineOfSight.rangeMeters / SPEED_OF_LIGHT_METERS_PER_SECOND - observation.propagationSeconds
        val atLower = evaluate(lower) ?: return invalid("unavailable GPS state at reception bracket")
        val atUpper = evaluate(upper) ?: return invalid("unavailable GPS state at reception bracket")
        if (residual(atLower) < -1e-13 || residual(atUpper) > 1e-13)
            return invalid("light-time solution outside absolute GPS fit interval")
        // Bracketing handles fit-interval edges without propagating an invalid trial epoch.
        repeat(50) {
            val result = evaluate(lower + (upper - lower) / 2)
                ?: return invalid("GPS light-time evaluation failed")
            val error = residual(result)
            if (abs(error) <= 1e-13) return GpsReceptionResult(result)
            if (error > 0) lower = result.propagationSeconds else upper = result.propagationSeconds
        }
        return invalid("GPS light-time equation did not converge")
    }
}
