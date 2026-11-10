package com.system.location.service.core.gnss

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

data class DecodedGpsLnav(
    val ephemeris: GpsLnavEphemeris,
    val uraIndex: Int,
    val frameTransmissionTime: GpsTime,
)

data class GpsLnavDecodeResult(val decoded: DecodedGpsLnav?, val reason: String? = null)

/**
 * IS-GPS-200N Figures 20-1, Tables 20-II/III/XII. Only a complete, same-frame CEI set is
 * accepted. [referenceAtSubframeOneCapture] must be trusted full GPS time at the capture of
 * subframe 1, not UTC or a modulo week. It resolves the 10-bit week, never supplies orbit data.
 */
object GpsLnavEphemerisDecoder {
    fun decode(subframes: Collection<GpsLnavSubframe>,
        referenceAtSubframeOneCapture: GpsTime): GpsLnavDecodeResult {
        fun invalid(reason: String) = GpsLnavDecodeResult(null, reason)
        if (subframes.size != 3 || subframes.map { it.id }.toSet() != setOf(1, 2, 3))
            return invalid("requires exactly GPS subframes 1, 2 and 3")
        if (subframes.map { it.svid }.distinct().size != 1) return invalid("mixed satellite identities")
        if (subframes.maxOf { it.capturedAtMillis } - subframes.minOf { it.capturedAtMillis } > 60_000)
            return invalid("subframe captures are not contemporaneous")
        if (subframes.any { it.alert }) return invalid("GPS HOW alert is raised")
        val (one, two, three) = subframes.sortedBy { it.id }
        val start = one.nextTowSeconds - 6
        if (two.nextTowSeconds - 12 != start || three.nextTowSeconds - 18 != start)
            return invalid("subframes belong to different transmission frames")

        val week10 = one.unsigned(48, 10).toInt()
        val ref = referenceAtSubframeOneCapture
        val era = ref.week / 1024
        val week = (era - 1..era + 1).map { it.toLong() * 1024 + week10 }
            .filter { it in 0..Int.MAX_VALUE.toLong() }
            .minByOrNull { abs(it - ref.week) }?.toInt() ?: return invalid("cannot resolve GPS week")
        val transmission = GpsTime(week, start.toDouble())
        if (abs(transmission.plusSeconds(6.0).secondsSince(ref)) > 120)
            return invalid("HOW week/time disagrees with trusted capture time")
        val iode2 = two.unsigned(48, 8).toInt()
        val iode3 = three.unsigned(216, 8).toInt()
        val iodc = (one.unsigned(70, 2).toInt() shl 8) or one.unsigned(168, 8).toInt()
        if (iode2 != iode3 || iode2 != iodc and 255) return invalid("inconsistent IODC/IODE")
        val health = one.unsigned(64, 6).toInt()
        val ura = one.unsigned(60, 4).toInt()
        if (health != 0 || ura == 15) return invalid("unhealthy GPS satellite or unavailable accuracy")
        val fit = fitHours(iodc, two.unsigned(232, 1) != 0L)
            ?: return invalid("reserved or inconsistent IODC/fit interval")
        val toeSeconds = two.unsigned(216, 16) * 16.0
        val tocSeconds = one.unsigned(176, 16) * 16.0
        if (toeSeconds >= 604800 || tocSeconds != toeSeconds)
            return invalid("invalid GPS toe/toc CEI epochs")
        // Resolve toe against transmission before any periodic propagator arithmetic.
        var toe = GpsTime(week, toeSeconds)
        if (toe.secondsSince(transmission) > 302400) {
            if (week == 0) return invalid("GPS epoch before week zero")
            toe = GpsTime(week - 1, toeSeconds)
        } else if (toe.secondsSince(transmission) < -302400) {
            if (week == Int.MAX_VALUE) return invalid("GPS week overflow")
            toe = GpsTime(week + 1, toeSeconds)
        }
        val halfFitSeconds = fit * 1800.0
        if (abs(transmission.secondsSince(toe)) > halfFitSeconds)
            return invalid("transmission lies outside orbit fit interval")
        if (one.signed(160, 8) == -128L) return invalid("GPS TGD is unavailable")

        return try {
            val e = GpsLnavEphemeris(one.svid, toe, toe, toe.plusSeconds(-halfFitSeconds),
                toe.plusSeconds(halfFitSeconds), iode2, iode3, iodc, health,
                two.unsigned(184, 32) * 2.0.pow(-19), two.unsigned(136, 32) * 2.0.pow(-33),
                three.scale(112, 32, -31, true), three.scale(64, 32, -31, true),
                three.scale(160, 32, -31, true), two.scale(88, 32, -31, true),
                two.scale(72, 16, -43, true), three.scale(192, 24, -43, true),
                three.scale(224, 14, -43, true), two.scale(120, 16, -29), two.scale(168, 16, -29),
                three.scale(144, 16, -5), two.scale(56, 16, -5),
                three.scale(48, 16, -29), three.scale(96, 16, -29),
                one.scale(216, 22, -31), one.scale(200, 16, -43), one.scale(192, 8, -55),
                one.scale(160, 8, -31))
            GpsLnavDecodeResult(DecodedGpsLnav(e, ura, transmission))
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid GPS broadcast parameters")
        }
    }

    private fun GpsLnavSubframe.scale(start: Int, length: Int, power: Int, semicircles: Boolean = false) =
        signed(start, length) * 2.0.pow(power) * if (semicircles) PI else 1.0

    /** Modern GPS blocks, Table 20-XII including reserved ranges (not old heuristic fallbacks). */
    internal fun fitHours(iodc: Int, extended: Boolean): Int? {
        if (iodc !in 0..1023 || iodc in 504..511 || iodc in 752..767 || iodc in 1008..1020) return null
        val normal = iodc in 0..239 || iodc in 256..495 || iodc in 512..751 || iodc in 768..1007
        if (!extended) return if (normal) 4 else null
        return when {
            normal -> 6
            iodc in 240..247 -> 8
            iodc in 248..255 || iodc == 496 -> 14
            iodc in 497..503 || iodc in 1021..1023 -> 26
            else -> null
        }
    }
}
