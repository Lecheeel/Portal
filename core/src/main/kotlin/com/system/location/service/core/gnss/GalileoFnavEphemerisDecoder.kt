package com.system.location.service.core.gnss

import kotlin.math.abs
import kotlin.math.pow

data class DecodedGalileoFnav(
    val ephemeris: GalileoBroadcastEphemeris,
    val transmissionTimeOfPageOne: GalileoTime,
    val e5aStatus: GalileoSignalStatus,
    val sisaIndex: Int,
    val bgdE1E5aSeconds: Double,
)
data class GalileoFnavDecodeResult(val decoded: DecodedGalileoFnav?, val reason: String? = null)

/** OS SIS ICD 2.2 Tables 30–33 and OS SDD 1.3 ephemeris age limit, as for I/NAV. */
object GalileoFnavEphemerisDecoder {
    private const val SEMICIRCLE_TO_RADIANS = 3.1415926535898

    fun decode(pages: Collection<GalileoNavigationPage>, trustedTimeAtPageOneCapture: GalileoTime,
        validFrom: GalileoTime? = null, validUntil: GalileoTime? = null): GalileoFnavDecodeResult {
        fun invalid(reason: String) = GalileoFnavDecodeResult(null, reason)
        if (pages.size != 4 || pages.map { it.wordType }.toSet() != (1..4).toSet() ||
            pages.any { it.format != GalileoClockReference.FNAV_E1_E5A })
            return invalid("requires exactly F/NAV pages 1/2/3/4")
        if (pages.map { it.svid }.distinct().size != 1) return invalid("mixed Galileo satellite identities")
        if (pages.maxOf { it.capturedAtMillis } - pages.minOf { it.capturedAtMillis } > 120_000)
            return invalid("F/NAV pages are not contemporaneous")
        val sorted = pages.sortedBy { it.wordType }
        val one = sorted[0]; val two = sorted[1]; val three = sorted[2]; val four = sorted[3]
        val iod = one.unsigned(12, 10).toInt()
        if (sorted.drop(1).any { it.unsigned(6, 10).toInt() != iod }) return invalid("inconsistent F/NAV IODnav")
        if (one.unsigned(6, 6).toInt() != one.svid) return invalid("F/NAV SVID disagrees with receiver metadata")
        val sisa = one.unsigned(94, 8).toInt()
        if (sisa !in 0..125) return invalid("F/NAV SISA is spare or NAPA")
        val ref = trustedTimeAtPageOneCapture
        fun resolve(week12: Int, tow: Double): GalileoTime? {
            if (tow >= 604800) return null
            val era = ref.week / 4096
            return (era - 1..era + 1).map { it.toLong() * 4096 + week12 }
                .filter { it in 0..Int.MAX_VALUE.toLong() }.map { GalileoTime(it.toInt(), tow) }
                .minByOrNull { abs(it.secondsSince(ref)) }
        }
        val transmission = resolve(one.unsigned(155, 12).toInt(), one.unsigned(167, 20).toDouble())
            ?: return invalid("invalid F/NAV GST epoch")
        if (abs(transmission.secondsSince(ref)) > 120) return invalid("F/NAV GST disagrees with trusted reference")
        val otherEpochs = listOf(resolve(two.unsigned(182, 12).toInt(), two.unsigned(194, 20).toDouble()),
            resolve(three.unsigned(174, 12).toInt(), three.unsigned(186, 20).toDouble()))
        if (otherEpochs.any { it == null || abs(it.secondsSince(transmission)) > 120 })
            return invalid("inconsistent F/NAV page week/time")
        fun nearest(seconds: Double): GalileoTime? = if (seconds >= 604800) null else
            (transmission.week.toLong() - 1..transmission.week.toLong() + 1)
                .filter { it in 0..Int.MAX_VALUE.toLong() }.map { GalileoTime(it.toInt(), seconds) }
                .minByOrNull { abs(it.secondsSince(transmission)) }
        val fourth = nearest(four.unsigned(189, 20).toDouble())
        if (fourth == null || abs(fourth.secondsSince(transmission)) > 120) return invalid("inconsistent F/NAV page 4 time")
        val toe = nearest(three.unsigned(160, 14) * 60.0) ?: return invalid("invalid F/NAV toe")
        val toc = nearest(one.unsigned(22, 14) * 60.0) ?: return invalid("invalid F/NAV toc")
        val validity = GalileoEphemerisValidity.resolve(toe, validFrom, validUntil)
            ?: return invalid("invalid Galileo ephemeris age interval")
        val allEpochs = listOf(transmission, fourth) + otherEpochs.filterNotNull()
        if (allEpochs.any { !validity.contains(it) })
            return invalid("F/NAV transmission outside Galileo ephemeris age/supplied validity interval")
        return try {
            val e = GalileoBroadcastEphemeris(one.svid, toe, toc, validity.from, validity.until, iod,
                GalileoClockReference.FNAV_E1_E5A, two.unsigned(104, 32) * 2.0.pow(-19),
                two.unsigned(72, 32) * 2.0.pow(-33), three.scale(16, 32, -31, true),
                two.scale(136, 32, -31, true), three.scale(48, 32, -31, true), two.scale(16, 32, -31, true),
                three.scale(80, 16, -43, true), two.scale(48, 24, -43, true), two.scale(168, 14, -43, true),
                three.scale(96, 16, -29), three.scale(112, 16, -29), three.scale(128, 16, -5), three.scale(144, 16, -5),
                four.scale(16, 16, -29), four.scale(32, 16, -29), one.scale(36, 31, -34), one.scale(67, 21, -46),
                one.scale(88, 6, -59))
            GalileoFnavDecodeResult(DecodedGalileoFnav(e, transmission,
                GalileoSignalStatus(one.unsigned(153, 2).toInt(), one.unsigned(187, 1).toInt()),
                sisa, one.scale(143, 10, -32)))
        } catch (error: IllegalArgumentException) { invalid(error.message ?: "invalid F/NAV parameters") }
    }

    private fun GalileoNavigationPage.scale(start: Int, length: Int, power: Int, semicircles: Boolean = false) =
        signed(start, length) * 2.0.pow(power) * if (semicircles) SEMICIRCLE_TO_RADIANS else 1.0
}
