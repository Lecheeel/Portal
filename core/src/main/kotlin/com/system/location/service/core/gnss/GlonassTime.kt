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
        atParts(coordinate.date, floor(coordinate.secondsOfDay).toInt(), coordinate.secondsOfDay % 1.0)

    internal fun atParts(date: LocalDate, second: Int, fraction: Double): GlonassTime? {
        val candidate = GlonassTime(this, date, second, fraction)
        return candidate.takeIf { it.secondsSinceCoordinate(from) >= 0 && it.secondsSinceCoordinate(until) < 0 }
    }
}

/** Full date and fractional seconds, tied to an explicit continuous correction/receiver domain. */
class GlonassTime internal constructor(val domain: GlonassTimeDomain, private val date: LocalDate,
    private val secondOfDay: Int, private val fraction: Double) {
    init { require(secondOfDay in 0..86399 && fraction.isFinite() && fraction >= 0 && fraction < 1) }
    /** Calendar label for display/schedule use; near-day-sized Doubles round fractional precision. */
    val coordinate: GlonassCalendarCoordinate get() {
        // Avoid exposing an invalid 86400 label if the display Double rounds just below midnight up.
        val displaySeconds = minOf(secondOfDay + fraction, Math.nextDown(86400.0))
        return GlonassCalendarCoordinate(date, displaySeconds)
    }

    fun secondsSince(other: GlonassTime): Double? = if (domain !== other.domain) null else
        (date.toEpochDay() - other.date.toEpochDay()) * 86400.0 + (secondOfDay - other.secondOfDay) + (fraction - other.fraction)

    internal fun secondsSinceCoordinate(other: GlonassCalendarCoordinate): Double =
        (date.toEpochDay() - other.date.toEpochDay()) * 86400.0 +
            (secondOfDay - floor(other.secondsOfDay).toInt()) + (fraction - other.secondsOfDay % 1.0)

    fun plusSeconds(seconds: Double): GlonassTime? {
        if (!seconds.isFinite()) return null
        val integer = floor(seconds)
        if (integer < Long.MIN_VALUE.toDouble() || integer >= Long.MAX_VALUE.toDouble()) return null
        val combinedFraction = fraction + (seconds - integer)
        val carry = floor(combinedFraction).toLong()
        val whole = try { Math.addExact(Math.addExact(integer.toLong(), secondOfDay.toLong()), carry) }
        catch (_: ArithmeticException) { return null }
        val days = Math.floorDiv(whole, 86400)
        val nextDate = try { date.plusDays(days) } catch (_: DateTimeException) { return null }
        catch (_: ArithmeticException) { return null }
        return domain.atParts(nextDate, Math.floorMod(whole, 86400), combinedFraction - carry)
    }
}
