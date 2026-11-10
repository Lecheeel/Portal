package com.system.location.service.core.gnss

import kotlin.math.pow
import kotlinx.serialization.Serializable

@Serializable
data class BeiDouD1Decoded(
    val ephemeris: BeiDouBroadcastEphemeris,
    val health: Int,
    val uraIndex: Int,
    val tgd1Seconds: Double,
    val tgd2Seconds: Double,
    val transmissionTime: BeiDouTime,
    val ageOfDataClock: Int,
    val ageOfDataEphemeris: Int,
)
data class BeiDouD1DecodeResult(val decoded: BeiDouD1Decoded?, val reason: String? = null)

/**
 * B1I 3.0 D1 subframes 1-3, Figures 5-7/8/9 and Tables 5-7/10. Full capture BDT
 * resolves the transmitted 13-bit week. Validity and MEO/IGSO type require external evidence;
 * AODC/AODE are ages, not issue identifiers or curve-fit durations. Health is returned as evidence.
 */
object BeiDouD1EphemerisDecoder {
    // B1I 3.0 Table 5-11 supplies this value for broadcast semicircle conversion.
    private const val SEMICIRCLE_TO_RADIANS = 3.1415926535898
    fun decode(subframes: Collection<BeiDouNavigationSubframe>, validFrom: BeiDouTime,
        validUntil: BeiDouTime, orbitType: BeiDouOrbitType,
        referenceAtSubframeOneCapture: BeiDouTime): BeiDouD1DecodeResult {
        fun invalid(reason: String) = BeiDouD1DecodeResult(null, reason)
        if (validUntil.secondsSince(validFrom) <= 0) return invalid("invalid external BeiDou validity interval")
        if (orbitType == BeiDouOrbitType.GEO) return invalid("D1 ephemeris cannot describe a GEO satellite")
        if (subframes.size != 3 || subframes.map { it.id }.toSet() != setOf(1, 2, 3) ||
            subframes.any { it.format != BeiDouNavigationFormat.D1 }) return invalid("requires D1 subframes 1/2/3")
        if (subframes.map { it.svid }.distinct().size != 1) return invalid("mixed BeiDou satellite identities")
        if (subframes.maxOf { it.capturedAtMillis } - subframes.minOf { it.capturedAtMillis } > 60_000)
            return invalid("D1 subframes are not contemporaneous")
        val ordered = subframes.sortedBy { it.id }
        val one = ordered[0]; val two = ordered[1]; val three = ordered[2]
        val sow1 = (one.rawUnsigned(18, 8) shl 12) or one.rawUnsigned(30, 12)
        val sow2 = (two.rawUnsigned(18, 8) shl 12) or two.rawUnsigned(30, 12)
        val sow3 = (three.rawUnsigned(18, 8) shl 12) or three.rawUnsigned(30, 12)
        fun follows(previous: Long, current: Long) = (current - previous + 604800L) % 604800L == 6L
        if (!follows(sow1, sow2) || !follows(sow2, sow3)) return invalid("inconsistent BeiDou D1 SOW sequence")
        val week13 = one.rawUnsigned(60, 13).toInt()
        val tocUnits = (one.rawUnsigned(73, 9) shl 8) or one.rawUnsigned(90, 8)
        val toc = tocUnits * 8.0
        val toeUnits = (two.rawUnsigned(290, 2) shl 15) or
            (three.rawUnsigned(42, 10) shl 5) or three.rawUnsigned(60, 5)
        val toe = toeUnits * 8.0
        val transmission = BeiDouDecodeEpochs.transmission(week13, sow1.toDouble(), referenceAtSubframeOneCapture)
            ?: return invalid("D1 transmission disagrees with trusted capture BDT")
        val toeEpoch = BeiDouDecodeEpochs.epoch(toe, transmission) ?: return invalid("invalid or ambiguous BeiDou toe")
        val tocEpoch = BeiDouDecodeEpochs.epoch(toc, transmission) ?: return invalid("invalid or ambiguous BeiDou toc")
        if (transmission.secondsSince(validFrom) < 0 || validUntil.secondsSince(transmission) < 0)
            return invalid("D1 transmission outside supplied validity evidence")
        return try {
            fun signedParts(source: BeiDouNavigationSubframe, a: Int, al: Int, b: Int, bl: Int): Long {
                val value = (source.rawUnsigned(a, al) shl bl) or source.rawUnsigned(b, bl)
                return if (source.rawUnsigned(a, 1) != 0L) value - (1L shl (al + bl)) else value
            }
            fun s2(a: Int, al: Int, b: Int, bl: Int) = signedParts(two, a, al, b, bl)
            fun s3(a: Int, al: Int, b: Int, bl: Int) = signedParts(three, a, al, b, bl)
            val e = BeiDouBroadcastEphemeris(one.svid, orbitType, toeEpoch,
                tocEpoch, validFrom, validUntil,
                ((two.rawUnsigned(250, 12) shl 20) or two.rawUnsigned(270, 20)) * 2.0.pow(-19),
                ((two.rawUnsigned(132, 10) shl 22) or two.rawUnsigned(150, 22)) * 2.0.pow(-33),
                s3(65, 17, 90, 15) * 2.0.pow(-31) * SEMICIRCLE_TO_RADIANS,
                s3(211, 21, 240, 11) * 2.0.pow(-31) * SEMICIRCLE_TO_RADIANS,
                s3(251, 11, 270, 21) * 2.0.pow(-31) * SEMICIRCLE_TO_RADIANS,
                s2(92, 20, 120, 12) * 2.0.pow(-31) * SEMICIRCLE_TO_RADIANS,
                s2(42, 10, 60, 6) * 2.0.pow(-43) * SEMICIRCLE_TO_RADIANS,
                s3(131, 11, 150, 13) * 2.0.pow(-43) * SEMICIRCLE_TO_RADIANS,
                s3(189, 13, 210, 1) * 2.0.pow(-43) * SEMICIRCLE_TO_RADIANS,
                s2(66, 16, 90, 2) * 2.0.pow(-31), two.rawSigned(180, 18) * 2.0.pow(-31),
                s2(198, 4, 210, 14) * 2.0.pow(-6), s2(224, 8, 240, 10) * 2.0.pow(-6),
                s3(105, 7, 120, 11) * 2.0.pow(-31), s3(163, 9, 180, 9) * 2.0.pow(-31),
                signedParts(one, 225, 7, 240, 17) * 2.0.pow(-33),
                signedParts(one, 257, 5, 270, 17) * 2.0.pow(-50),
                one.rawSigned(214, 11) * 2.0.pow(-66))
            BeiDouD1DecodeResult(BeiDouD1Decoded(e, one.rawUnsigned(42, 1).toInt(), one.rawUnsigned(48, 4).toInt(),
                one.rawSigned(98, 10) * 0.1e-9,
                signedParts(one, 108, 4, 120, 6) * 0.1e-9,
                transmission, one.rawUnsigned(43, 5).toInt(), one.rawUnsigned(287, 5).toInt()))
        } catch (error: IllegalArgumentException) { invalid(error.message ?: "invalid BeiDou D1 parameters") }
    }
}
