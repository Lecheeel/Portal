package com.system.location.service.core.gnss

import java.util.Base64

/** A parity-checked GPS L1 C/A subframe, with inversion and parity removed. */
class GpsLnavSubframe internal constructor(
    val svid: Int,
    val capturedAtMillis: Long,
    val id: Int,
    val nextTowSeconds: Int,
    val alert: Boolean,
    data: ByteArray,
) {
    private val bits = data.copyOf()

    /** Zero-based bit position in the 240-bit parity-free subframe, MSB first. */
    fun unsigned(start: Int, length: Int): Long {
        require(start >= 0 && length in 1..32 && start + length <= 240)
        var value = 0L
        repeat(length) { offset ->
            val index = start + offset
            value = (value shl 1) or ((bits[index / 8].toInt() ushr (7 - index % 8)) and 1).toLong()
        }
        return value
    }

    fun signed(start: Int, length: Int): Long {
        val value = unsigned(start, length)
        return if (value and (1L shl (length - 1)) != 0L) value - (1L shl length) else value
    }
}

data class GpsLnavWordResult(val subframe: GpsLnavSubframe?, val reason: String? = null)

/**
 * Android's 40-byte GPS LNAV format: ten big-endian words with two zero padding bits.
 * IS-GPS-200N 20.3.5 / Table 20-XIV: D30* inverts data only, not the six parity bits.
 * Receiver status never substitutes for checking the actual words. No error correction is attempted.
 */
object GpsLnavWords {
    private val parityMasks = longArrayOf(0xBB1F3480, 0x5D8F9A40, 0xAEC7CD00,
        0x5763E680, 0x6BB1F340, 0x8B7A89C0)

    fun decode(observation: GnssNavigationObservation): GpsLnavWordResult {
        fun invalid(reason: String) = GpsLnavWordResult(null, reason)
        if (observation.type != 0x0101 || observation.svid !in 1..32)
            return invalid("not a GPS L1 C/A message")
        if (observation.status !in 0..2) return invalid("invalid navigation parity status")
        val payload = Base64.getDecoder().decode(observation.dataBase64)
        if (payload.size != 40) return invalid("GPS LNAV requires exactly ten 30-bit words")
        val words = LongArray(10) { index ->
            (0..3).fold(0L) { value, byte -> (value shl 8) or (payload[index * 4 + byte].toLong() and 255) }
        }
        if (words.any { it ushr 30 != 0L }) return invalid("nonzero Android word padding")
        // These two parity bits are forced to zero by the non-information bits in HOW/word 10.
        if (words[1] and 3 != 0L || words[9] and 3 != 0L) return invalid("invalid HOW/subframe parity termination")

        // Previous word's D29*/D30* are absent from Android's payload. Recover them by testing
        // all four possibilities against the first word's parity and preamble, never by guessing.
        val first = (0L..3L).mapNotNull { previous ->
            decodeWord(words[0], previous)?.takeIf { it ushr 16 == 0x8B }
        }.singleOrNull() ?: return invalid("TLM preamble or parity failure")
        val data = ByteArray(30)
        for (index in words.indices) {
            val decoded = if (index == 0) first else decodeWord(words[index], words[index - 1] and 3)
                ?: return invalid("word ${index + 1} parity failure")
            for (byte in 0..2) data[index * 3 + byte] = (decoded ushr (16 - byte * 8)).toByte()
        }
        val how = ((data[3].toInt() and 255) shl 16) or ((data[4].toInt() and 255) shl 8) or
            (data[5].toInt() and 255)
        val id = (how ushr 2) and 7
        val towCount = how ushr 7
        if (id !in 1..5 || id != observation.submessageId) return invalid("HOW/submessage identity mismatch")
        if (towCount >= 100800 || towCount % 5 != id % 5) return invalid("invalid HOW time/subframe phase")
        return GpsLnavWordResult(GpsLnavSubframe(observation.svid, observation.capturedAtMillis,
            id, towCount * 6, how and 64 != 0, data))
    }

    private fun decodeWord(transmitted: Long, previous: Long): Int? {
        var word = (previous shl 30) or transmitted
        if (previous and 1 != 0L) word = word xor 0x3FFFFFC0
        var parity = 0
        for (mask in parityMasks) parity = (parity shl 1) or (java.lang.Long.bitCount(word and mask) and 1)
        return if (parity.toLong() == transmitted and 63) ((word ushr 6) and 0xFFFFFF).toInt() else null
    }
}
