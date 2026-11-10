package com.system.location.service.core.gnss

import java.util.Base64

enum class BeiDouNavigationFormat { D1, D2 }

/** 224 information bits after BCH verification/deinterleaving (26 + 9*22), MSB first. */
class BeiDouNavigationSubframe internal constructor(
    val svid: Int,
    val capturedAtMillis: Long,
    val format: BeiDouNavigationFormat,
    val id: Int,
    /** D1: this subframe's first bit. D2: subframe 1's first bit in the containing frame. */
    val secondsOfWeek: Int,
    information: ByteArray,
) {
    private val bytes = information.copyOf()
    /** Read an ICD bit range in the original 300-bit word layout, skipping BCH parity gaps. */
    fun rawUnsigned(start: Int, length: Int): Long {
        require(start >= 0 && length in 1..63 && start + length <= 300)
        var value = 0L
        repeat(length) { offset ->
            val raw = start + offset
            val word = raw / 30
            val within = raw % 30
            require(if (word == 0) within < 26 else within < 22) { "range includes BeiDou BCH parity" }
            val info = if (word == 0) within else 26 + (word - 1) * 22 +
                if (within % 2 == 0) within / 2 else 11 + within / 2
            value = (value shl 1) or ((bytes[info / 8].toInt() ushr (7 - info % 8)) and 1).toLong()
        }
        return value
    }

    fun rawSigned(start: Int, length: Int): Long {
        val raw = rawUnsigned(start, length)
        return if (raw and (1L shl (length - 1)) != 0L) raw - (1L shl length) else raw
    }
    fun unsigned(start: Int, length: Int): Long {
        require(start >= 0 && length in 1..63 && start + length <= 224)
        var value = 0L
        repeat(length) { offset ->
            val bit = start + offset
            value = (value shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1).toLong()
        }
        return value
    }
    fun signed(start: Int, length: Int): Long {
        val raw = unsigned(start, length)
        return if (raw and (1L shl (length - 1)) != 0L) raw - (1L shl length) else raw
    }
}

data class BeiDouNavigationWordResult(val subframe: BeiDouNavigationSubframe?, val reason: String? = null)

/**
 * B1I 3.0 §§5.1.3, 5.2.2/4, 5.3.1/3. Android packs ten 30-bit transmitted words
 * into 40 bytes, MSB first, with two leading padding bits per word. First-word leading
 * 15 bits are unprotected; its lower 15 bits use BCH. Remaining words interleave two
 * systematic BCH(15,11) codewords using g(x)=x^4+x+1. We verify every syndrome, including
 * payloads labelled PARITY_PASSED/REBUILT by the HAL, and do not silently correct bad bits.
 */
object BeiDouNavigationWords {
    fun decode(observation: GnssNavigationObservation): BeiDouNavigationWordResult {
        fun invalid(reason: String) = BeiDouNavigationWordResult(null, reason)
        val format = when (observation.type) {
            0x0501 -> BeiDouNavigationFormat.D1
            0x0502 -> BeiDouNavigationFormat.D2
            else -> return invalid("requires BeiDou D1 or D2 navigation data")
        }
        if (observation.svid !in 1..63 || observation.status !in 0..2)
            return invalid("invalid BeiDou identity or parity status")
        val payload = Base64.getDecoder().decode(observation.dataBase64)
        if (payload.size != 40) return invalid("BeiDou Android payload must contain ten padded 30-bit words")
        val words = IntArray(10) { index ->
            (0..3).fold(0) { word, byte -> (word shl 8) or (payload[index * 4 + byte].toInt() and 255) }
        }
        if (words.any { it ushr 30 != 0 }) return invalid("nonzero BeiDou Android word padding")
        if (words[0] ushr 19 != 0x712) return invalid("invalid BeiDou preamble")
        val information = ByteArray(28)
        var position = 0
        fun append(value: Int, count: Int) = repeat(count) { offset ->
            val bit = (value ushr (count - offset - 1)) and 1
            information[position / 8] = (information[position / 8].toInt() or (bit shl (7 - position % 8))).toByte()
            position++
        }
        if (syndrome(words[0] and 0x7fff) != 0) return invalid("BeiDou word 1 BCH failure")
        append(words[0] ushr 4, 26)
        for (index in 1..9) {
            var first = 0
            var second = 0
            repeat(15) { bit ->
                first = (first shl 1) or ((words[index] ushr (29 - bit * 2)) and 1)
                second = (second shl 1) or ((words[index] ushr (28 - bit * 2)) and 1)
            }
            if (syndrome(first) != 0 || syndrome(second) != 0) return invalid("BeiDou word ${index + 1} BCH failure")
            append(first ushr 4, 11)
            append(second ushr 4, 11)
        }
        val preliminary = BeiDouNavigationSubframe(observation.svid, observation.capturedAtMillis, format, 0, 0, information)
        val id = preliminary.unsigned(15, 3).toInt()
        val sow = (preliminary.rawUnsigned(18, 8) shl 12 or preliminary.rawUnsigned(30, 12)).toInt()
        if (id !in 1..5 || id != observation.submessageId) return invalid("BeiDou subframe/metadata identity mismatch")
        if (sow >= 604800) return invalid("BeiDou SOW outside week")
        if (format == BeiDouNavigationFormat.D1 && sow % 30 != (id - 1) * 6)
            return invalid("BeiDou D1 SOW/subframe phase mismatch")
        if (format == BeiDouNavigationFormat.D2 && sow % 3 != 0)
            return invalid("BeiDou D2 containing-frame SOW phase mismatch")
        return BeiDouNavigationWordResult(BeiDouNavigationSubframe(observation.svid, observation.capturedAtMillis,
            format, id, sow, information))
    }

    private fun syndrome(codeword: Int): Int {
        var remainder = codeword
        for (bit in 14 downTo 4) if (remainder and (1 shl bit) != 0)
            remainder = remainder xor (0x13 shl (bit - 4))
        return remainder and 15
    }
}
