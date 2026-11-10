package com.system.location.service.core.gnss

import kotlin.math.*

data class GlonassOrbitalState(
    val referenceFrame: GlonassReferenceFrame,
    val positionMeters: GlonassCartesianVector,
    val velocityMetersPerSecond: GlonassCartesianVector,
)

data class GlonassPropagationResult(val state: GlonassOrbitalState?, val reason: String? = null)

/**
 * ICD 5.1 A.3.1.2 simplified rotating-ECEF dynamics, with corrected y Coriolis and z J2 terms.
 * RK4 with at most 10 SI seconds per step. This is orbital mathematics, NOT a usable signal.
 * The caller supplies an independently resolved continuous SI interval from tb; subtracting
 * ambiguous civil-day/UTC/GPS labels (especially across a leap) does not establish that interval.
 * No implicit datum conversion, clock/relativity correction or signal-flight rotation is applied.
 */
object GlonassBroadcastPropagator {
    const val EARTH_MU = 3.9860044e14
    const val EARTH_RADIUS_METERS = 6_378_136.0
    const val EARTH_J2 = 1.0826257e-3
    const val EARTH_ROTATION_RAD_PER_SECOND = 7.292115e-5
    private const val STEP_SECONDS = 10.0

    fun propagate(e: GlonassBroadcastStateVector, secondsFromReference: Double): GlonassPropagationResult {
        fun invalid() = GlonassPropagationResult(null, "invalid GLONASS integration state")
        // ICD A.3.1.1 uses strict <15 minutes; no modulo-day/week revival or extrapolation.
        if (!secondsFromReference.isFinite() || abs(secondsFromReference) >= 900.0)
            return GlonassPropagationResult(null, "outside GLONASS integration interval")
        var state = doubleArrayOf(e.positionMeters.x, e.positionMeters.y, e.positionMeters.z,
            e.velocityMetersPerSecond.x, e.velocityMetersPerSecond.y, e.velocityMetersPerSecond.z)
        val acc = e.luniSolarAccelerationMetersPerSecondSquared
        val steps = ceil(abs(secondsFromReference) / STEP_SECONDS).toInt()
        var elapsed = 0.0
        repeat(steps) {
            val remaining = secondsFromReference - elapsed
            val h = sign(remaining) * min(STEP_SECONDS, abs(remaining))
            fun shifted(k: DoubleArray, scale: Double) = DoubleArray(6) { i -> state[i] + scale * k[i] }
            val k1 = derivative(state, acc) ?: return invalid()
            val k2 = derivative(shifted(k1, h / 2), acc) ?: return invalid()
            val k3 = derivative(shifted(k2, h / 2), acc) ?: return invalid()
            val k4 = derivative(shifted(k3, h), acc) ?: return invalid()
            state = DoubleArray(6) { i -> state[i] + h / 6 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) }
            elapsed += h
        }
        if (derivative(state, acc) == null) return invalid()
        return GlonassPropagationResult(GlonassOrbitalState(e.referenceFrame,
            GlonassCartesianVector(state[0], state[1], state[2]),
            GlonassCartesianVector(state[3], state[4], state[5])))
    }

    internal fun derivative(s: DoubleArray, acc: GlonassCartesianVector): DoubleArray? {
        if (s.size != 6 || !s.all(Double::isFinite)) return null
        val x = s[0]; val y = s[1]; val z = s[2]
        val r2 = x * x + y * y + z * z
        if (!r2.isFinite() || r2 <= EARTH_RADIUS_METERS * EARTH_RADIUS_METERS) return null
        val r3 = r2 * sqrt(r2)
        val j = 1.5 * EARTH_J2 * EARTH_MU * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / (r2 * r3)
        val b = 5 * z * z / r2
        val gravity = -EARTH_MU / r3
        val omega = EARTH_ROTATION_RAD_PER_SECOND
        // a_rot = a_grav - 2*Omega cross v - Omega cross (Omega cross r) + a_luniSolar.
        // J2_z has (3-5*z²/r²), unlike x/y's (1-5*z²/r²).
        return doubleArrayOf(s[3], s[4], s[5],
            (gravity - j * (1 - b) + omega * omega) * x + 2 * omega * s[4] + acc.x,
            (gravity - j * (1 - b) + omega * omega) * y - 2 * omega * s[3] + acc.y,
            (gravity - j * (3 - b)) * z + acc.z).takeIf { it.all(Double::isFinite) }
    }
}
