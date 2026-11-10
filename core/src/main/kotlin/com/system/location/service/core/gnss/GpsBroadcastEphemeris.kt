package com.system.location.service.core.gnss

import kotlin.math.floor

/** Full GPS system time, not UTC, Android elapsed time, or a truncated broadcast week. */
data class GpsTime(val week: Int, val secondsOfWeek: Double) {
    init {
        require(week >= 0 && secondsOfWeek.isFinite() && secondsOfWeek >= 0 && secondsOfWeek < SECONDS_PER_WEEK)
    }

    fun secondsSince(other: GpsTime): Double =
        (week.toLong() - other.week.toLong()).toDouble() * SECONDS_PER_WEEK + secondsOfWeek - other.secondsOfWeek

    fun plusSeconds(seconds: Double): GpsTime {
        require(seconds.isFinite())
        val total = secondsOfWeek + seconds
        val weekChange = floor(total / SECONDS_PER_WEEK)
        val nextWeek = week.toDouble() + weekChange
        require(nextWeek >= 0 && nextWeek <= Int.MAX_VALUE)
        return GpsTime(nextWeek.toInt(), total - weekChange * SECONDS_PER_WEEK)
    }

    companion object { const val SECONDS_PER_WEEK = 604_800.0 }
}

/**
 * GPS LNAV parameters after parity, full-week resolution and subframe assembly.
 * Angular fields are radians, rates radians/second, clock coefficients seconds/second^n.
 * The decoder must supply the actual absolute fit interval; it cannot infer completeness from
 * GnssStatus.hasEphemeris. This type does not represent Galileo, BDS GEO or GLONASS ephemerides.
 * IS-GPS-200N 20.3.3.4, Tables 20-II/III/IV.
 */
data class GpsLnavEphemeris(
    val svid: Int,
    val toe: GpsTime,
    val toc: GpsTime,
    val validFrom: GpsTime,
    val validUntil: GpsTime,
    val iode2: Int,
    val iode3: Int,
    val iodc: Int,
    val health: Int,
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
    val tgdSeconds: Double,
) {
    init {
        require(svid in 1..32)
        require(iode2 in 0..255 && iode3 == iode2 && iodc in 0..1023 && iodc and 255 == iode2) {
            "inconsistent LNAV issue of data"
        }
        require(health in 0..63)
        require(toe.secondsSince(validFrom) >= 0 && validUntil.secondsSince(toe) >= 0 &&
            validUntil.secondsSince(validFrom) > 0) { "invalid absolute fit interval" }
        require(sqrtSemiMajorAxis in 2530.0..8192.0 && eccentricity in 0.0..0.03) {
            "GPS LNAV orbit outside specified range"
        }
        require(listOf(inclinationRad, ascendingNodeRad, argumentOfPerigeeRad, meanAnomalyRad,
            deltaMeanMotionRadPerSecond, ascendingNodeRateRadPerSecond, inclinationRateRadPerSecond,
            cucRad, cusRad, crcMeters, crsMeters, cicRad, cisRad,
            af0Seconds, af1SecondsPerSecond, af2SecondsPerSecondSquared, tgdSeconds).all(Double::isFinite))
    }
}
