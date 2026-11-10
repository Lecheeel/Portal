package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

/** Full BeiDou Time since its own week origin; no GPS/GST/UTC conversion is implicit. */
@Serializable
data class BeiDouTime(val week: Int, val secondsOfWeek: Double) {
    init { require(week >= 0 && secondsOfWeek.isFinite() && secondsOfWeek >= 0 && secondsOfWeek < 604800) }
    fun secondsSince(other: BeiDouTime) = (week.toLong() - other.week.toLong()) * 604800.0 + secondsOfWeek - other.secondsOfWeek
    fun plusSeconds(seconds: Double): BeiDouTime {
        require(seconds.isFinite())
        val result = requireNotNull(GnssWeekTime.normalize(week.toLong(), secondsOfWeek + seconds))
        return BeiDouTime(result.first, result.second)
    }
}

@Serializable
enum class BeiDouOrbitType { GEO, IGSO, MEO }

/**
 * B1I 3.0 Table 5-10 decoded orbital/clock parameters. All angles are radians.
 * Orbit type is explicit evidence, never a heuristic based on a historical PRN range.
 * Validity must be independently established; an hourly update period/AODE is not a fit flag.
 * Raw D1/D2 validation, assembly, health and per-signal TGD are separate requirements.
 */
@Serializable
data class BeiDouBroadcastEphemeris(
    val svid: Int,
    val orbitType: BeiDouOrbitType,
    val toe: BeiDouTime,
    val toc: BeiDouTime,
    val validFrom: BeiDouTime,
    val validUntil: BeiDouTime,
    val sqrtSemiMajorAxis: Double,
    val eccentricity: Double,
    val inclinationRad: Double,
    val ascendingNodeRad: Double,
    val argumentOfPerigeeRad: Double,
    val meanAnomalyRad: Double,
    val deltaMeanMotionRadPerSecond: Double,
    val ascendingNodeRateRadPerSecond: Double,
    val inclinationRateRadPerSecond: Double,
    val cucRad: Double,
    val cusRad: Double,
    val crcMeters: Double,
    val crsMeters: Double,
    val cicRad: Double,
    val cisRad: Double,
    val af0Seconds: Double,
    val af1SecondsPerSecond: Double,
    val af2SecondsPerSecondSquared: Double,
) {
    init {
        require(svid in 1..63)
        require(validUntil.secondsSince(validFrom) > 0)
        require(sqrtSemiMajorAxis.isFinite() && sqrtSemiMajorAxis > 0 && sqrtSemiMajorAxis < 8192)
        require(eccentricity >= 0 && eccentricity < 0.5)
        require(listOf(inclinationRad, ascendingNodeRad, argumentOfPerigeeRad, meanAnomalyRad,
            deltaMeanMotionRadPerSecond, ascendingNodeRateRadPerSecond, inclinationRateRadPerSecond,
            cucRad, cusRad, crcMeters, crsMeters, cicRad, cisRad, af0Seconds, af1SecondsPerSecond,
            af2SecondsPerSecondSquared).all(Double::isFinite))
    }
}
