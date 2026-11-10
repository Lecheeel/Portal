package com.system.location.service.core.gnss

import java.time.DateTimeException
import java.time.LocalDate
import kotlin.math.floor

/**
 * A calendar coordinate on ONE continuous GLONASS correction scale, with uniform 86400s days.
 * This is not a civil UTC leap-second representation. ICD Appendix 2 requires pre-correction
 * ephemerides to retain the old continuous scale until superseded; corrected labels form a
 * different domain. Neither UTC(SU)/UTC/GPS offsets nor leap transitions are inferred here.
 */
data class GlonassCalendarCoordinate(val date: LocalDate, val secondsOfDay: Double) {
    init { require(secondsOfDay.isFinite() && secondsOfDay >= 0 && secondsOfDay < 86400) }
    internal fun secondsSince(other: GlonassCalendarCoordinate) =
        (date.toEpochDay() - other.date.toEpochDay()) * 86400.0 + secondsOfDay - other.secondsOfDay

    internal fun plusSeconds(seconds: Double): GlonassCalendarCoordinate? {
        if (!seconds.isFinite()) return null
        val total = secondsOfDay + seconds
        if (!total.isFinite()) return null
        val days = floor(total / 86400.0)
        if (days < Long.MIN_VALUE.toDouble() || days >= Long.MAX_VALUE.toDouble()) return null
        val normalized = total - days * 86400.0
        if (normalized < 0 || normalized >= 86400) return null
        return try { GlonassCalendarCoordinate(date.plusDays(days.toLong()), normalized) }
        catch (_: DateTimeException) { null }
        catch (_: ArithmeticException) { null }
    }

    companion object {
        /** Complete broadcast N4/NT, not a nearest-century guess or a repeated 1461-day modulo. */
        fun fromBroadcastDate(n4: Int, nt: Int, secondsOfDay: Double): GlonassCalendarCoordinate? {
            if (n4 !in 1..31 || nt !in 1..1461 || !secondsOfDay.isFinite() || secondsOfDay < 0 || secondsOfDay >= 86400)
                return null
            val start = LocalDate.of(1996 + 4 * (n4 - 1), 1, 1)
            val date = start.plusDays((nt - 1).toLong())
            if (!date.isBefore(start.plusYears(4))) return null
            return GlonassCalendarCoordinate(date, secondsOfDay)
        }
    }
}

/**
 * Independently established continuous clock coverage [from, until). The caller must provide
 * actual correction/clock evidence for this entire interval; a diagnostic ID is not proof.
 * Create distinct instances for pre/post UTC corrections and receiver discontinuities.
 * This object makes no claim that a particular navigation callback has an emission timestamp.
 */
class GlonassTimeDomain(val evidenceId: String, val from: GlonassCalendarCoordinate, val until: GlonassCalendarCoordinate) {
    init { require(evidenceId.isNotBlank() && evidenceId.length <= 256 && until.secondsSince(from) > 0) }

    fun at(coordinate: GlonassCalendarCoordinate): GlonassTime? =
        if (coordinate.secondsSince(from) < 0 || until.secondsSince(coordinate) <= 0) null
        else GlonassTime(this, coordinate)
}

/** Full date and fractional seconds, tied to an explicit continuous correction/receiver domain. */
class GlonassTime internal constructor(val domain: GlonassTimeDomain, val coordinate: GlonassCalendarCoordinate) {
    fun secondsSince(other: GlonassTime): Double? =
        if (domain !== other.domain) null else coordinate.secondsSince(other.coordinate)

    fun plusSeconds(seconds: Double): GlonassTime? = coordinate.plusSeconds(seconds)?.let(domain::at)
}
