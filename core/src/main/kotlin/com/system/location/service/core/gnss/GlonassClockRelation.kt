package com.system.location.service.core.gnss

import kotlin.math.abs

data class GlonassClockBiasResult(val satelliteMinusSystemSeconds: Double?, val reason: String? = null)
data class GlonassClockTimeResult(val systemTime: GlonassTime?, val reason: String? = null)

/**
 * ICD 5.1 §3.3.3 affine clock relation on one established continuous correction domain:
 * U = S + tauC + tauN - gammaN * (S - tb), where U is UTC(SU)+3h and S is onboard time.
 * tb and U share the domain of referenceTime; an onboard reading is never labelled GlonassTime.
 * tauC is mandatory; no GPS/UTC(SU) correction, integer leap offset or zero fallback is inferred.
 * gammaN already includes the broadcast gravitational/relativistic frequency prediction (§4.4).
 *
 * validFrom/validUntil must be independently established CLOCK applicability, not the orbital
 * integration limit, En or P1. This relation alone says nothing about health or signal usability.
 */
data class GlonassClockRelation(
    val referenceTime: GlonassTime,
    val tauNSeconds: Double,
    val gammaN: Double,
    val tauCSeconds: Double,
    val validFrom: GlonassTime,
    val validUntil: GlonassTime,
) {
    init {
        // Sign-magnitude field capacities in Tables 4.5 and 4.9 (GLONASS-M tauC).
        require(tauNSeconds.isFinite() && abs(tauNSeconds) <= 2_097_151 * Math.scalb(1.0, -30))
        require(gammaN.isFinite() && abs(gammaN) <= 1_023 * Math.scalb(1.0, -40))
        require(tauCSeconds.isFinite() && abs(tauCSeconds) <= 2_147_483_647 * Math.scalb(1.0, -31))
        val from = validFrom.secondsSince(referenceTime)
        val until = validUntil.secondsSince(referenceTime)
        require(from != null && until != null && from <= 0 && until >= 0 && until > from)
    }

    private fun contains(time: GlonassTime): Boolean =
        time.secondsSince(validFrom)?.let { it >= 0 } == true &&
            validUntil.secondsSince(time)?.let { it >= 0 } == true

    /** Solve the implicit onboard reading at a SYSTEM epoch; return S-U, in seconds. */
    fun biasAtSystemTime(systemTime: GlonassTime): GlonassClockBiasResult {
        if (!contains(systemTime)) return GlonassClockBiasResult(null, "outside GLONASS clock applicability or correction domain")
        val dt = systemTime.secondsSince(referenceTime)!!
        // S-U = (-tauC-tauN + gammaN*(U-tb)) / (1-gammaN).
        // Omitting the denominator substitutes a system time into a satellite-time polynomial.
        val bias = (-tauCSeconds - tauNSeconds + gammaN * dt) / (1.0 - gammaN)
        return if (bias.isFinite()) GlonassClockBiasResult(bias)
            else GlonassClockBiasResult(null, "non-finite GLONASS clock bias")
    }

    /**
     * A separately associated complete onboard reading expressed as S-tb, not a seconds-of-day
     * label. The caller must resolve its date; no nearest-day wrapping is performed here.
     */
    fun systemTimeFromSatelliteOffset(satelliteSecondsSinceReference: Double): GlonassClockTimeResult {
        if (!satelliteSecondsSinceReference.isFinite())
            return GlonassClockTimeResult(null, "non-finite GLONASS onboard time offset")
        val offset = satelliteSecondsSinceReference + tauCSeconds + tauNSeconds - gammaN * satelliteSecondsSinceReference
        val time = referenceTime.plusSeconds(offset)
            ?: return GlonassClockTimeResult(null, "GLONASS clock conversion outside continuous time coverage")
        return if (contains(time)) GlonassClockTimeResult(time)
            else GlonassClockTimeResult(null, "outside GLONASS clock applicability interval")
    }
}
