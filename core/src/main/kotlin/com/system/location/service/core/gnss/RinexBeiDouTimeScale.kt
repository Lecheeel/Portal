package com.system.location.service.core.gnss

/**
 * RINEX 3.05 A5 BDUT is a fractional BDT-UTC polynomial at a native BDT reference week.
 * BDT is continuous, with TAI-BDT=33 seconds from its 2006-01-01 UTC origin. GPS-BDT's
 * nominal 14-second relation is NOT the full fractional time correction.
 *
 * Solve b = nominalBDT + a0 + a1*(b-reference), including the (1-a1) denominator.
 * The caller supplies an application age bound, not an invented broadcast validity duration.
 * Nominal UTC realizations and millisecond-resolution inverse are suitable for sky geometry,
 * not a precision timing claim or a raw UTC/BDT leap-transition navigation decoder.
 * https://files.igs.org/pub/data/format/rinex305.pdf
 * https://en.beidou.gov.cn/SYSTEMS/ICD/201902/P020190227702348791891.pdf
 */
class RinexBeiDouTimeScale(
    private val utc: GnssUtcTimeScale,
    private val correction: RinexTimeSystemCorrection,
    private val maximumCorrectionDistanceSeconds: Double,
) {
    val reference = BeiDouTime(correction.referenceWeek, correction.referenceSeconds.toDouble())

    init {
        require(correction.type == "BDUT")
        require(maximumCorrectionDistanceSeconds.isFinite() && maximumCorrectionDistanceSeconds > 0)
    }

    fun atUtcMillis(utcMillis: Long): BeiDouTime? {
        if (correction.a1SecondsPerSecond >= 1) return null
        val gps = utc.gpsAtUtcMillis(utcMillis).time ?: return null
        val nominal = GnssWeekTime.normalize(gps.week.toLong() - GPS_WEEK_ORIGIN, gps.secondsOfWeek - GPS_BDT_SECONDS)
            ?.let { BeiDouTime(it.first, it.second) } ?: return null
        val offset = (correction.a0Seconds + correction.a1SecondsPerSecond * nominal.secondsSince(reference)) /
            (1 - correction.a1SecondsPerSecond)
        if (!offset.isFinite()) return null
        val result = GnssWeekTime.normalize(nominal.week.toLong(), nominal.secondsOfWeek + offset)
            ?.let { BeiDouTime(it.first, it.second) } ?: return null
        return result.takeIf { withinAge(it) }
    }

    fun utcAt(time: BeiDouTime): Long? {
        if (correction.a1SecondsPerSecond >= 1 || !withinAge(time)) return null
        val offset = correction.a0Seconds + correction.a1SecondsPerSecond * time.secondsSince(reference)
        if (!offset.isFinite()) return null
        val gps = GnssWeekTime.normalize(time.week.toLong() + GPS_WEEK_ORIGIN,
            time.secondsOfWeek - offset + GPS_BDT_SECONDS)?.let { GpsTime(it.first, it.second) } ?: return null
        return utc.utcAtGpsTime(gps).utcMillis
    }

    private fun withinAge(time: BeiDouTime) = kotlin.math.abs(time.secondsSince(reference)) <= maximumCorrectionDistanceSeconds

    companion object {
        private const val GPS_WEEK_ORIGIN = 1356L
        private const val GPS_BDT_SECONDS = 14.0
    }
}
