package com.system.location.service.core.gnss

import kotlin.math.abs

data class DecodedGalileoGpsOffset(
    val offset: GalileoGpsTimeOffset,
    /** F/NAV page 4 includes TOW; I/NAV word 10 has no transmission timestamp. */
    val transmissionTime: GalileoTime?,
)
data class GalileoGpsOffsetDecodeResult(val decoded: DecodedGalileoGpsOffset?, val reason: String? = null)

/**
 * CRC-checked I/NAV word 10 or F/NAV page 4, OS SIS ICD 2.2 Tables 33/51/76, Eq. 24.
 * The caller must establish a GPS interval containing the broadcast time-tag epoch, including
 * transmitter-clock uncertainty, not merely callback arrival. A decoded GST tag is not a
 * precision physical emission epoch. This decoder invents no callback delay bound. The 120s interval is
 * a bounded association policy, not a GGTO validity duration or timestamp accuracy claim.
 *
 * Resolve six-bit WN0G by evaluating candidate models against independent GPS evidence;
 * do not require an already-converted GST clock to bootstrap GPS-to-GST conversion.
 * The ±31-week constraint applies at broadcast, including a window that crosses a week.
 */
object GalileoGpsOffsetDecoder {
    fun decode(page: GalileoNavigationPage, broadcastGpsFrom: GpsTime, broadcastGpsUntil: GpsTime,
        validFrom: GalileoTime, validUntil: GalileoTime): GalileoGpsOffsetDecodeResult {
        fun invalid(reason: String) = GalileoGpsOffsetDecodeResult(null, reason)
        val inav = page.format == GalileoClockReference.INAV_E1_E5B && page.wordType == 10
        val fnav = page.format == GalileoClockReference.FNAV_E1_E5A && page.wordType == 4
        if (!inav && !fnav) return invalid("page does not contain broadcast GGTO")
        if (broadcastGpsUntil.secondsSince(broadcastGpsFrom) !in 0.0..120.0)
            return invalid("invalid or excessive GPS transmission association window")
        if (validUntil.secondsSince(validFrom) <= 0) return invalid("invalid external GGTO validity evidence")
        val a0 = page.signed(if (inav) 86 else 155, 16).toInt()
        val a1 = page.signed(if (inav) 102 else 171, 12).toInt()
        val hour = page.unsigned(if (inav) 114 else 147, 8).toInt()
        val week6 = page.unsigned(if (inav) 122 else 183, 6).toInt()
        if (a0 == -1 && a1 == -1 && hour == 255 && week6 == 63)
            return invalid("broadcast GGTO is unavailable")
        val tow = if (fnav) page.unsigned(189, 20).toDouble() else null
        if (tow != null && tow >= 604800) return invalid("invalid F/NAV transmission TOW")
        val nominalWeek = broadcastGpsFrom.week.toLong() - GalileoGpsTimeOffset.GPS_WEEK_ORIGIN
        val era = Math.floorDiv(nominalWeek, 64L)
        val possible = (era - 1..era + 1).map { it * 64 + week6 }
            .filter { it in 0..Int.MAX_VALUE.toLong() }.mapNotNull { referenceWeek ->
                val model = GalileoGpsTimeOffset(referenceWeek.toInt(), hour, a0, a1, validFrom, validUntil)
                val from = model.gpsToGalileo(broadcastGpsFrom).time ?: return@mapNotNull null
                val until = model.gpsToGalileo(broadcastGpsUntil).time ?: return@mapNotNull null
                if (tow == null) {
                    // Word 10 has no GST timestamp. Existence within the supplied transmission
                    // interval resolves the reference week; it does not timestamp the word.
                    val firstWeek = maxOf(from.week.toLong(), referenceWeek - 31)
                    val lastWeek = minOf(until.week.toLong(), referenceWeek + 31)
                    if (firstWeek > lastWeek) null else DecodedGalileoGpsOffset(model, null)
                } else {
                    val transmission = (from.week..until.week).map { GalileoTime(it, tow) }.filter {
                        it.secondsSince(from) >= 0 && until.secondsSince(it) >= 0 &&
                            abs(it.week.toLong() - referenceWeek) <= 31
                    }.singleOrNull() ?: return@mapNotNull null
                    DecodedGalileoGpsOffset(model, transmission)
                }
            }
        return when (possible.size) {
            1 -> GalileoGpsOffsetDecodeResult(possible.single())
            0 -> invalid("no full GGTO reference week agrees with supplied GPS transmission and validity evidence")
            else -> invalid("ambiguous full GGTO reference week")
        }
    }
}
