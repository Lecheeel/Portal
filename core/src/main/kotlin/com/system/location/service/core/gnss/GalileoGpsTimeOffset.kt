package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

data class GpsToGalileoResult(val time: GalileoTime?, val reason: String? = null)
data class GalileoToGpsResult(val time: GpsTime?, val reason: String? = null)

/**
 * Galileo OS SIS ICD 2.2 Eq. 24/Table 76. Full WN0G must already be resolved from
 * independent time evidence. Raw signed coefficient units preserve the broadcast field bounds.
 * Validity is external evidence, not an invented 31-week fit interval: the ±31-week rule
 * constrains WN0G resolution at broadcast, not the lifetime of this polynomial.
 *
 * GPS week 1024 and GST week 0 share the nominal epoch. Changing the week origin is NOT
 * applying GGTO. No UTC/leap-second constant, satellite-clock correction, or receiver bias
 * is folded into this inter-system time conversion. It does not establish time accuracy.
 */
@Serializable
data class GalileoGpsTimeOffset(
    val referenceWeek: Int,
    val referenceHour: Int,
    val a0Units: Int,
    val a1Units: Int,
    val validFrom: GalileoTime,
    val validUntil: GalileoTime,
) {
    init {
        require(referenceWeek >= 0 && referenceHour in 0..255)
        require(a0Units in -32768..32767 && a1Units in -2048..2047)
        require(validUntil.secondsSince(validFrom) > 0)
        require(!(a0Units == -1 && a1Units == -1 && referenceHour == 255 && referenceWeek % 64 == 63)) {
            "broadcast GGTO unavailable sentinel"
        }
    }

    val a0Seconds: Double get() = Math.scalb(a0Units.toDouble(), -35)
    val a1SecondsPerSecond: Double get() = Math.scalb(a1Units.toDouble(), -51)

    fun gpsToGalileo(time: GpsTime): GpsToGalileoResult {
        val nominalWeek = time.week.toLong() - GPS_WEEK_ORIGIN
        val delta = (nominalWeek - referenceWeek) * 604800.0 + time.secondsOfWeek - referenceHour * 3600.0
        // GST - GPS = a0 + a1*(GST - t0). Solve for GST, rather than evaluating
        // the right-hand side at GPS and silently omitting the (1-a1) denominator.
        val correction = (a0Seconds + a1SecondsPerSecond * delta) / (1.0 - a1SecondsPerSecond)
        val normalized = GnssWeekTime.normalize(nominalWeek, time.secondsOfWeek + correction)
            ?: return GpsToGalileoResult(null, "converted GST is outside supported full-week range")
        val result = GalileoTime(normalized.first, normalized.second)
        return if (withinValidity(result)) GpsToGalileoResult(result)
            else GpsToGalileoResult(null, "GGTO conversion outside supplied validity evidence")
    }

    fun galileoToGps(time: GalileoTime): GalileoToGpsResult {
        if (!withinValidity(time)) return GalileoToGpsResult(null, "GGTO conversion outside supplied validity evidence")
        val delta = (time.week.toLong() - referenceWeek) * 604800.0 + time.secondsOfWeek - referenceHour * 3600.0
        val correction = a0Seconds + a1SecondsPerSecond * delta
        val normalized = GnssWeekTime.normalize(time.week.toLong() + GPS_WEEK_ORIGIN, time.secondsOfWeek - correction)
            ?: return GalileoToGpsResult(null, "converted GPS time is outside supported full-week range")
        return GalileoToGpsResult(GpsTime(normalized.first, normalized.second))
    }

    private fun withinValidity(time: GalileoTime) =
        time.secondsSince(validFrom) >= 0 && validUntil.secondsSince(time) >= 0

    companion object {
        // An epoch-number relation, never an assumption that the physical time offset is zero.
        const val GPS_WEEK_ORIGIN = 1024L
    }
}
