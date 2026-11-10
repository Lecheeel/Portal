package com.system.location.service.core.gnss

import kotlin.math.pow

data class BeiDouD2Decoded(
    val ephemeris: BeiDouBroadcastEphemeris,
    val health: Int,
    val uraIndex: Int,
    val tgd1Seconds: Double,
    val tgd2Seconds: Double,
    val transmissionTimeOfPageOne: BeiDouTime,
    val ageOfDataClock: Int,
    val ageOfDataEphemeris: Int,
)
data class BeiDouD2DecodeResult(val decoded: BeiDouD2Decoded?, val reason: String? = null)

/**
 * B1I 3.0 Figures 5-14-1..10: GEO subframe 1 pages 1 and 3..10 carry orbit/clock.
 * Page 2 is optional ionospheric evidence, not required to complete an ephemeris. Parameters
 * split across pages are joined before signed conversion. External validity remains mandatory.
 */
object BeiDouD2EphemerisDecoder {
    private const val SEMICIRCLE_TO_RADIANS = 3.1415926535898
    internal val requiredPages: Set<Int> = setOf(1, 3, 4, 5, 6, 7, 8, 9, 10)

    fun decode(pages: Collection<BeiDouNavigationSubframe>, validFrom: BeiDouTime,
        validUntil: BeiDouTime, referenceAtPageOneCapture: BeiDouTime): BeiDouD2DecodeResult {
        fun invalid(reason: String) = BeiDouD2DecodeResult(null, reason)
        if (validUntil.secondsSince(validFrom) <= 0) return invalid("invalid external BeiDou validity interval")
        if (pages.any { it.format != BeiDouNavigationFormat.D2 || it.id != 1 })
            return invalid("requires BeiDou D2 subframe 1 pages")
        val numbered = pages.map { it.rawUnsigned(42, 4).toInt() to it }
        if (numbered.map { it.first }.distinct().size != numbered.size ||
            numbered.any { it.first !in 1..10 } || !numbered.map { it.first }.containsAll(requiredPages))
            return invalid("requires unique D2 pages 1 and 3..10")
        if (pages.map { it.svid }.distinct().size != 1) return invalid("mixed BeiDou satellite identities")
        if (pages.maxOf { it.capturedAtMillis } - pages.minOf { it.capturedAtMillis } > 60_000)
            return invalid("D2 pages are not contemporaneous")
        val p = numbered.toMap()
        val one = p.getValue(1)
        val sow1 = one.secondsOfWeek
        for ((number, page) in numbered) {
            val expectedSow = (sow1 + (number - 1) * 3) % 604800
            if (page.secondsOfWeek != expectedSow) return invalid("inconsistent D2 page SOW sequence")
        }
        val transmission = BeiDouDecodeEpochs.transmission(one.rawUnsigned(64, 13).toInt(),
            sow1.toDouble(), referenceAtPageOneCapture)
            ?: return invalid("D2 transmission disagrees with trusted capture BDT")
        if (transmission.secondsSince(validFrom) < 0 || validUntil.secondsSince(transmission) < 27)
            return invalid("D2 transmission cycle outside supplied validity evidence")
        // Each triplet is (page, ICD diagram offset, bit length); no wire interleaving here.
        fun bits(vararg fields: Int, signed: Boolean = false): Long {
            var value = 0L
            var length = 0
            for (i in fields.indices step 3) {
                value = (value shl fields[i + 2]) or p.getValue(fields[i]).rawUnsigned(fields[i + 1], fields[i + 2])
                length += fields[i + 2]
            }
            return if (signed && value and (1L shl (length - 1)) != 0L) value - (1L shl length) else value
        }
        fun scaled(power: Int, vararg fields: Int) = bits(*fields, signed = true) * 2.0.pow(power)
        val toe = BeiDouDecodeEpochs.epoch(bits(7, 80, 2, 7, 90, 15) * 8.0, transmission)
            ?: return invalid("invalid or ambiguous BeiDou D2 toe")
        val toc = BeiDouDecodeEpochs.epoch(bits(1, 77, 5, 1, 90, 12) * 8.0, transmission)
            ?: return invalid("invalid or ambiguous BeiDou D2 toc")
        return try {
            val e = BeiDouBroadcastEphemeris(one.svid, BeiDouOrbitType.GEO, toe, toc, validFrom, validUntil,
                bits(6, 76, 6, 6, 90, 22, 6, 120, 4) * 2.0.pow(-19),
                bits(5, 124, 10, 6, 46, 6, 6, 60, 16) * 2.0.pow(-33),
                scaled(-31, 7, 105, 7, 7, 120, 14, 8, 46, 6, 8, 60, 5) * SEMICIRCLE_TO_RADIANS,
                scaled(-31, 9, 51, 1, 9, 60, 22, 9, 90, 9) * SEMICIRCLE_TO_RADIANS,
                scaled(-31, 9, 99, 13, 9, 120, 14, 10, 46, 5) * SEMICIRCLE_TO_RADIANS,
                scaled(-31, 5, 50, 2, 5, 60, 22, 5, 90, 8) * SEMICIRCLE_TO_RADIANS,
                scaled(-43, 4, 96, 16) * SEMICIRCLE_TO_RADIANS,
                scaled(-43, 8, 109, 3, 8, 120, 16, 9, 46, 5) * SEMICIRCLE_TO_RADIANS,
                scaled(-43, 10, 51, 1, 10, 60, 13) * SEMICIRCLE_TO_RADIANS,
                scaled(-31, 4, 120, 14, 5, 46, 4), scaled(-31, 5, 98, 14, 5, 120, 4),
                scaled(-6, 8, 65, 17, 8, 90, 1), scaled(-6, 8, 91, 18),
                scaled(-31, 6, 124, 10, 7, 46, 6, 7, 60, 2), scaled(-31, 7, 62, 18),
                scaled(-33, 3, 100, 12, 3, 120, 12), scaled(-50, 3, 132, 4, 4, 46, 6, 4, 60, 12),
                scaled(-66, 4, 72, 10, 4, 90, 1))
            BeiDouD2DecodeResult(BeiDouD2Decoded(e, bits(1, 46, 1).toInt(), bits(1, 60, 4).toInt(),
                bits(1, 102, 10, signed = true) * 0.1e-9, bits(1, 120, 10, signed = true) * 0.1e-9,
                transmission, bits(1, 47, 5).toInt(), bits(4, 91, 5).toInt()))
        } catch (error: IllegalArgumentException) { invalid(error.message ?: "invalid BeiDou D2 parameters") }
    }
}
