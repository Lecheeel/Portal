package com.system.location.service.core.gnss

/** Full Galileo System Time (GST) week/seconds, never a GPS-week or UTC alias. */
data class GalileoTime(val week: Int, val secondsOfWeek: Double) {
    init { require(week >= 0 && secondsOfWeek.isFinite() && secondsOfWeek >= 0 && secondsOfWeek < 604800) }
    fun secondsSince(other: GalileoTime) = (week.toLong() - other.week.toLong()) * 604800.0 + secondsOfWeek - other.secondsOfWeek
    fun plusSeconds(seconds: Double): GalileoTime {
        require(seconds.isFinite())
        val normalized = requireNotNull(GnssWeekTime.normalize(week.toLong(), secondsOfWeek + seconds)) {
            "GST outside supported full-week range"
        }
        return GalileoTime(normalized.first, normalized.second)
    }
}

enum class GalileoClockReference { INAV_E1_E5B, FNAV_E1_E5A }

/**
 * Galileo OS SIS ICD 2.2 Tables 67/70/78. CRC/page assembly and IODnav consistency must be
 * established before constructing this decoded model. Absolute validity is supplied by the
 * decoder using Galileo's forward age limit and any additional independent restrictions.
 * Reference epochs toe/toc need not lie inside a shortened operational interval.
 * All angular parameters are radians; rates use seconds. No GPS/GST/UTC conversion is implicit.
 */
data class GalileoBroadcastEphemeris(
    val svid: Int,
    val toe: GalileoTime,
    val toc: GalileoTime,
    val validFrom: GalileoTime,
    val validUntil: GalileoTime,
    val iodnav: Int,
    val clockReference: GalileoClockReference,
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
        require(svid in 1..36 && iodnav in 0..1023)
        require(validUntil.secondsSince(validFrom) > 0)
        // Unsigned bit widths/scales in Table 67; no invented nominal MEO radius/eccentricity.
        require(sqrtSemiMajorAxis.isFinite() && sqrtSemiMajorAxis > 0 && sqrtSemiMajorAxis < 8192)
        require(eccentricity >= 0 && eccentricity < 0.5)
        require(listOf(inclinationRad, ascendingNodeRad, argumentOfPerigeeRad, meanAnomalyRad,
            deltaMeanMotionRadPerSecond, ascendingNodeRateRadPerSecond, inclinationRateRadPerSecond,
            cucRad, cusRad, crcMeters, crsMeters, cicRad, cisRad, af0Seconds, af1SecondsPerSecond,
            af2SecondsPerSecondSquared).all(Double::isFinite))
    }
}
