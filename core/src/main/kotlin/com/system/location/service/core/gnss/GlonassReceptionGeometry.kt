package com.system.location.service.core.gnss

import kotlin.math.*

/** Explicit position/local ellipsoid-normal orientation in the same PZ-90 realization as the orbit. */
data class GlonassReceiver(val referenceFrame: GlonassReferenceFrame,
    val position: EcefPosition, val orientation: GeodeticOrientation)

data class GlonassReceptionObservation(
    val transmissionTime: GlonassTime,
    val propagationSeconds: Double,
    val satelliteInReceptionEcef: EcefPosition,
    val lineOfSight: GnssLineOfSight,
)

data class GlonassReceptionResult(val observation: GlonassReceptionObservation?, val reason: String? = null)

/**
 * Vacuum geometric light-time on one independently established continuous correction scale.
 * Epochs are UTC(SU)+3h coordinates on that scale, not receiver/satellite clock readings.
 * Rz(-Omega*flightTime) rotates the emission ECEF position into reception axes exactly once.
 * No tauC, satellite clock, atmospheric delay, signal usability or pseudorange is inferred.
 */
object GlonassReceptionGeometry {
    const val SPEED_OF_LIGHT_METERS_PER_SECOND = 299792458.0

    fun observe(e: GlonassOrbitalEphemeris, receptionTime: GlonassTime,
        receiver: GlonassReceiver): GlonassReceptionResult {
        fun invalid(reason: String) = GlonassReceptionResult(null, reason)
        if (receiver.referenceFrame != e.orbit.referenceFrame) return invalid("GLONASS receiver/orbit reference frames differ")
        val sinceFrom = receptionTime.secondsSince(e.validFrom)
            ?: return invalid("different GLONASS reception correction/receiver time domain")
        val sinceUntil = receptionTime.secondsSince(e.validUntil)!!
        // Use the whole independently established applicability interval as the bracket.
        // There is no invented orbital-radius or nominal signal-flight-time ceiling.
        var lower = max(0.0, sinceUntil)
        var upper = sinceFrom
        if (lower > upper) return invalid("no GLONASS emission inside orbital applicability interval")
        fun evaluate(delay: Double): GlonassReceptionObservation? {
            // Preserve exact validity endpoints rather than introduce subtract/add rounding outside them.
            val transmission = when (delay) {
                sinceFrom -> e.validFrom
                sinceUntil -> e.validUntil
                else -> receptionTime.plusSeconds(-delay) ?: return null
            }
            val state = GlonassAbsolutePropagator.propagate(e, transmission).state ?: return null
            val p = state.positionMeters
            val angle = GlonassBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND * delay
            val rotated = EcefPosition(cos(angle) * p.x + sin(angle) * p.y,
                -sin(angle) * p.x + cos(angle) * p.y, p.z)
            val los = GnssObserverGeometry.lineOfSight(receiver.position, receiver.orientation, rotated) ?: return null
            return GlonassReceptionObservation(transmission, delay, rotated, los)
        }
        fun residual(o: GlonassReceptionObservation) = o.lineOfSight.rangeMeters / SPEED_OF_LIGHT_METERS_PER_SECOND - o.propagationSeconds
        val atLower = evaluate(lower) ?: return invalid("unavailable GLONASS state at reception bracket")
        val atUpper = evaluate(upper) ?: return invalid("unavailable GLONASS state at reception bracket")
        if (residual(atLower) < -1e-13 || residual(atUpper) > 1e-13)
            return invalid("GLONASS light-time solution outside orbital applicability interval")
        if (abs(residual(atLower)) <= 1e-13) return GlonassReceptionResult(atLower)
        if (abs(residual(atUpper)) <= 1e-13) return GlonassReceptionResult(atUpper)
        repeat(60) {
            val o = evaluate(lower + (upper - lower) / 2) ?: return invalid("GLONASS light-time evaluation failed")
            val error = residual(o)
            if (abs(error) <= 1e-13) return GlonassReceptionResult(o)
            if (error > 0) lower = o.propagationSeconds else upper = o.propagationSeconds
        }
        return invalid("GLONASS light-time equation did not converge")
    }
}
