package com.system.location.service.core.gnss

import java.util.Base64

/** Immutable ICD-numbered information bits, after all eight Hamming checks have passed. */
class GlonassNavigationString internal constructor(
    val slot: Int,
    val capturedAtMillis: Long,
    val frameId: Int,
    val stringId: Int,
    payload: ByteArray,
) {
    private val bytes = payload.copyOf()

    /** ICD bits descend from highBit; parity bits 1..8 and idle bit 85 are excluded. */
    fun unsigned(highBit: Int, length: Int): Long {
        require(highBit in 9..84 && length in 1..32 && highBit - length + 1 >= 9)
        return (0 until length).fold(0L) { value, offset ->
            val index = 88 - highBit + offset
            (value shl 1) or ((bytes[index / 8].toInt() ushr (7 - index % 8)) and 1).toLong()
        }
    }

    /** GLONASS uses sign-magnitude, including a negative-zero representation, not two's complement. */
    fun signMagnitude(highBit: Int, length: Int): Long {
        require(length >= 2)
        val value = unsigned(highBit, length)
        val magnitude = value and ((1L shl (length - 1)) - 1)
        return if (value and (1L shl (length - 1)) != 0L) -magnitude else magnitude
    }
}

data class GlonassStringResult(val string: GlonassNavigationString?, val reason: String? = null)

/**
 * ICD 5.1 §4.7/Table 4.13. Android GLO L1 C/A uses an 11-byte big-endian 85-bit integer,
 * skipping high bits B86..B88. This differs from RTKLIB's left-aligned 85-bit buffer.
 * All syndromes must be zero, even for HAL PARITY_PASSED/REBUILT. No error correction or
 * ambiguous automatic bit alignment is performed; a parity error is rejected conservatively.
 */
object GlonassNavigationStrings {
    fun decode(o: GnssNavigationObservation): GlonassStringResult {
        fun invalid(reason: String) = GlonassStringResult(null, reason)
        if (o.type != 0x0301 || o.svid !in 1..31 || o.status !in 0..2)
            return invalid("requires GLONASS L1 C/A with a known slot and valid parity status")
        if (o.messageId !in 1..5 || o.submessageId !in 1..15)
            return invalid("invalid GLONASS frame or string metadata")
        val input = Base64.getDecoder().decode(o.dataBase64)
        if (input.size != 11 || input[0].toInt() and 0xe0 != 0)
            return invalid("invalid GLONASS Android payload length or leading padding")
        fun bit(icd: Int): Int {
            val index = 88 - icd
            return (input[index / 8].toInt() ushr (7 - index % 8)) and 1
        }
        if (bit(85) != 0) return invalid("invalid GLONASS idle chip")
        for (check in 0..6) {
            val parity = CHECK_BITS[check].fold(bit(check + 1)) { value, position -> value xor bit(position) }
            if (parity != 0) return invalid("GLONASS Hamming syndrome failure")
        }
        if ((1..85).fold(0) { value, position -> value xor bit(position) } != 0)
            return invalid("GLONASS overall parity failure")
        val id = (84 downTo 81).fold(0) { value, position -> (value shl 1) or bit(position) }
        if (id != o.submessageId) return invalid("GLONASS string identity mismatch")
        return GlonassStringResult(GlonassNavigationString(o.svid, o.capturedAtMillis, o.messageId, id, input))
    }

    // Explicit ICD bit sets, independent of the byte masks used by the C verification oracle.
    private val CHECK_BITS = listOf(
        listOf(9,10,12,13,15,17,19,20,22,24,26,28,30,32,34,35,37,39,41,43,45,47,49,51,53,55,57,59,61,63,65,66,68,70,72,74,76,78,80,82,84),
        listOf(9,11,12,14,15,18,19,21,22,25,26,29,30,33,34,36,37,40,41,44,45,48,49,52,53,56,57,60,61,64,65,67,68,71,72,75,76,79,80,83,84),
        listOf(10..12,16..19,23..26,31..34,38..41,46..49,54..57,62..65,69..72,77..80,85..85).flatMap { it },
        listOf(13..19,27..34,42..49,58..65,73..80).flatMap { it },
        listOf(20..34,50..65,81..85).flatMap { it },
        (35..65).toList(),
        (66..85).toList(),
    )
}
