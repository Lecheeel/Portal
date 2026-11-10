package com.system.location.service.core.gnss

import kotlin.math.abs

data class GlonassReferenceEpochResult(val time: GlonassTime?, val reason: String? = null)

/** Resolve the UTC(SU)+3h tb label using a complete, independently established frame schedule date. */
object GlonassReferenceEpoch {
    fun resolve(dayInFourYearInterval: Int, tbSecondsOfDay: Int, frameSchedule: GlonassCalendarCoordinate,
        domain: GlonassTimeDomain): GlonassReferenceEpochResult {
        fun invalid(reason: String) = GlonassReferenceEpochResult(null, reason)
        if (tbSecondsOfDay !in 900..85500 || tbSecondsOfDay % 900 != 0)
            return invalid("invalid GLONASS tb label")
        if (domain.at(frameSchedule) == null) return invalid("GLONASS schedule outside clock coverage")
        val date = frameSchedule.date
        val n4 = Math.floorDiv(date.year - 1996, 4) + 1
        val broadcast = GlonassCalendarCoordinate.fromBroadcastDate(n4, dayInFourYearInterval, frameSchedule.secondsOfDay)
        if (broadcast?.date != date) return invalid("GLONASS NT disagrees with complete schedule date")
        // tb may refer to an adjacent day. A full date stays attached; a stale day cannot wrap.
        val options = (-1L..1L).map { GlonassCalendarCoordinate(date.plusDays(it), tbSecondsOfDay.toDouble()) }
            .sortedBy { abs(it.secondsSince(frameSchedule)) }
        if (abs(options[0].secondsSince(frameSchedule)) == abs(options[1].secondsSince(frameSchedule)))
            return invalid("ambiguous GLONASS reference day")
        val reference = domain.at(options[0]) ?: return invalid("GLONASS tb outside independently established clock coverage")
        return GlonassReferenceEpochResult(reference)
    }
}
