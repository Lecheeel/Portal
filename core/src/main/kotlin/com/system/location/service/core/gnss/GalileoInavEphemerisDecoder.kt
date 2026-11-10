package com.system.location.service.core.gnss

import kotlin.math.abs
import kotlin.math.pow

data class GalileoSignalStatus(val health: Int, val dataValidity: Int) {
    init { require(health in 0..3 && dataValidity in 0..1) }
    val usable: Boolean get() = health == 0 && dataValidity == 0
}

data class DecodedGalileoInav(
    val ephemeris: GalileoBroadcastEphemeris,
    val transmissionTimeOfWordFive: GalileoTime,
    val e1Status: GalileoSignalStatus,
    val e5bStatus: GalileoSignalStatus,
    val sisaIndex: Int,
    val bgdE1E5aSeconds: Double,
    val bgdE1E5bSeconds: Double,
)
data class GalileoInavDecodeResult(val decoded: DecodedGalileoInav?, val reason: String? = null)

/**
 * OS SIS ICD 2.2 Tables 42–46/67/70/78/91. Decode complete CRC-checked I/NAV words 1–5.
 * Galileo carries no GPS LNAV fit flag. OS SDD 1.3 limits age to [toe,toe+4h]; optional
 * independent limits may only shorten that interval. This does not establish future signal
 * health or OSNMA authentication. Health/DVS are never merged into one healthy bit.
 */
object GalileoInavEphemerisDecoder {
    private const val SEMICIRCLE_TO_RADIANS = 3.1415926535898

    fun decode(pages: Collection<GalileoNavigationPage>, trustedTimeAtWordFiveCapture: GalileoTime,
        validFrom: GalileoTime? = null, validUntil: GalileoTime? = null): GalileoInavDecodeResult {
        fun invalid(reason: String) = GalileoInavDecodeResult(null, reason)
        if (pages.size != 5 || pages.map { it.wordType }.toSet() != (1..5).toSet() ||
            pages.any { it.format != GalileoClockReference.INAV_E1_E5B })
            return invalid("requires exactly I/NAV words 1/2/3/4/5")
        if (pages.map { it.svid }.distinct().size != 1) return invalid("mixed Galileo satellite identities")
        if (pages.maxOf { it.capturedAtMillis } - pages.minOf { it.capturedAtMillis } > 120_000)
            return invalid("I/NAV pages are not contemporaneous")
        val sorted = pages.sortedBy { it.wordType }
        val one = sorted[0]; val two = sorted[1]; val three = sorted[2]; val four = sorted[3]; val five = sorted[4]
        val issues = sorted.take(4).map { it.unsigned(6, 10).toInt() }
        if (issues.distinct().size != 1) return invalid("inconsistent Galileo IODnav")
        if (four.unsigned(16, 6).toInt() != one.svid) return invalid("I/NAV SVID disagrees with receiver metadata")
        val sisa = three.unsigned(120, 8).toInt()
        if (sisa !in 0..125) return invalid("Galileo SISA is spare or NAPA")
        val week12 = five.unsigned(73, 12).toInt()
        val tow = five.unsigned(85, 20).toDouble()
        if (tow >= 604800) return invalid("invalid GST time of week")
        val ref = trustedTimeAtWordFiveCapture
        val era = ref.week / 4096
        val week = (era - 1..era + 1).map { it.toLong() * 4096 + week12 }
            .filter { it in 0..Int.MAX_VALUE.toLong() }
            .minByOrNull { abs(it - ref.week) }?.toInt() ?: return invalid("cannot resolve full GST week")
        val transmission = GalileoTime(week, tow)
        if (abs(transmission.secondsSince(ref)) > 120) return invalid("GST page time disagrees with trusted capture reference")
        fun resolveEpoch(seconds: Double): GalileoTime? {
            if (seconds >= 604800) return null
            return (week.toLong() - 1..week.toLong() + 1).filter { it in 0..Int.MAX_VALUE.toLong() }
                .map { GalileoTime(it.toInt(), seconds) }.minByOrNull { abs(it.secondsSince(transmission)) }
        }
        val toe = resolveEpoch(one.unsigned(16, 14) * 60.0) ?: return invalid("invalid Galileo toe")
        val toc = resolveEpoch(four.unsigned(54, 14) * 60.0) ?: return invalid("invalid Galileo toc")
        val validity = GalileoEphemerisValidity.resolve(toe, validFrom, validUntil)
            ?: return invalid("invalid Galileo ephemeris age interval")
        if (!validity.contains(transmission))
            return invalid("transmission outside Galileo ephemeris age/supplied validity interval")
        return try {
            val e = GalileoBroadcastEphemeris(one.svid, toe, toc, validity.from, validity.until, issues.first(),
                GalileoClockReference.INAV_E1_E5B, one.unsigned(94, 32) * 2.0.pow(-19),
                one.unsigned(62, 32) * 2.0.pow(-33), two.scale(48, 32, -31, true),
                two.scale(16, 32, -31, true), two.scale(80, 32, -31, true), one.scale(30, 32, -31, true),
                three.scale(40, 16, -43, true), three.scale(16, 24, -43, true), two.scale(112, 14, -43, true),
                three.scale(56, 16, -29), three.scale(72, 16, -29), three.scale(88, 16, -5), three.scale(104, 16, -5),
                four.scale(22, 16, -29), four.scale(38, 16, -29), four.scale(68, 31, -34),
                four.scale(99, 21, -46), four.scale(120, 6, -59))
            GalileoInavDecodeResult(DecodedGalileoInav(e, transmission,
                GalileoSignalStatus(five.unsigned(69, 2).toInt(), five.unsigned(72, 1).toInt()),
                GalileoSignalStatus(five.unsigned(67, 2).toInt(), five.unsigned(71, 1).toInt()),
                sisa, five.scale(47, 10, -32), five.scale(57, 10, -32)))
        } catch (error: IllegalArgumentException) { invalid(error.message ?: "invalid Galileo parameters") }
    }

    private fun GalileoNavigationPage.scale(start: Int, length: Int, power: Int, semicircles: Boolean = false) =
        signed(start, length) * 2.0.pow(power) * if (semicircles) SEMICIRCLE_TO_RADIANS else 1.0
}
