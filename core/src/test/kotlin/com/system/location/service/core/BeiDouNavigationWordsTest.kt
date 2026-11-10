package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class BeiDouNavigationWordsTest {
    @Test fun verifiesAndExtractsD1AndD2WordsWithoutTrustingHalParityLabel() {
        for (format in listOf(BeiDouNavigationFormat.D1, BeiDouNavigationFormat.D2)) {
            val observation = observation(format)
            val decoded = BeiDouNavigationWords.decode(observation)
            assertNotNull(decoded.reason, decoded.subframe)
            assertEquals(format, decoded.subframe!!.format)
            assertEquals(24, decoded.subframe!!.svid)
            assertEquals(3, decoded.subframe!!.id)
            assertEquals(if (format == BeiDouNavigationFormat.D1) 432012 else 432000,
                decoded.subframe!!.secondsOfWeek)
            assertEquals(0x1A5L, decoded.subframe!!.unsigned(70, 9))
        }
    }

    @Test fun rejectsPreamblePaddingPhaseIdentityLengthAndSingleBitBchErrors() {
        val original = observation(BeiDouNavigationFormat.D1)
        val payload = Base64.getDecoder().decode(original.dataBase64)
        val invalid = listOf(
            original.copy(dataBase64 = Base64.getEncoder().encodeToString(payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })),
            original.copy(dataBase64 = Base64.getEncoder().encodeToString(payload.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() })),
            original.copy(submessageId = 2),
            original.copy(dataBase64 = Base64.getEncoder().encodeToString(payload.copyOfRange(0, 39))),
        )
        invalid.forEach { assertNull(BeiDouNavigationWords.decode(it).subframe) }
        val unknown = original.copy(type = 0x0503)
        assertNull(BeiDouNavigationWords.decode(unknown).subframe)
    }

    @Test fun keepsD2FrameSowDistinctFromD1SubframeSow() {
        val d1 = BeiDouNavigationWords.decode(observation(BeiDouNavigationFormat.D1)).subframe!!
        val d2 = BeiDouNavigationWords.decode(observation(BeiDouNavigationFormat.D2)).subframe!!
        assertNotEquals(d1.secondsOfWeek, d2.secondsOfWeek)
        assertEquals(432000, d2.secondsOfWeek)
    }

    private fun observation(format: BeiDouNavigationFormat): GnssNavigationObservation {
        val information = BooleanArray(224)
        fun set(start: Int, length: Int, value: Int) = repeat(length) { bit ->
            information[start + bit] = value ushr (length - bit - 1) and 1 != 0
        }
        set(0, 11, 0b11100010010)
        set(15, 3, 3)
        set(18, 20, if (format == BeiDouNavigationFormat.D1) 432012 else 432000)
        set(70, 9, 0x1A5)
        val words = IntArray(10)
        fun get(start: Int, length: Int): Int = (0 until length).fold(0) { value, bit ->
            (value shl 1) or if (information[start + bit]) 1 else 0
        }
        words[0] = (get(0, 26) shl 4) or bch(get(15, 11))
        var cursor = 26
        for (index in 1..9) {
            val first = get(cursor, 11); val second = get(cursor + 11, 11); cursor += 22
            var word = 0
            repeat(11) { bit -> word = word or ((first ushr (10 - bit) and 1) shl (29 - bit * 2)) }
            repeat(11) { bit -> word = word or ((second ushr (10 - bit) and 1) shl (28 - bit * 2)) }
            val p1 = bch(first); val p2 = bch(second)
            repeat(4) { bit -> word = word or ((p1 ushr (3 - bit) and 1) shl (7 - bit * 2)) }
            repeat(4) { bit -> word = word or ((p2 ushr (3 - bit) and 1) shl (6 - bit * 2)) }
            words[index] = word
        }
        val bytes = ByteArray(40)
        words.forEachIndexed { index, word -> repeat(4) { byte ->
            bytes[index * 4 + byte] = (word ushr (24 - byte * 8)).toByte()
        } }
        return GnssNavigationObservation(1, if (format == BeiDouNavigationFormat.D1) 0x0501 else 0x0502,
            24, 1, 3, 3, Base64.getEncoder().encodeToString(bytes))
    }

    private fun bch(data: Int): Int {
        var remainder = data shl 4
        for (bit in 14 downTo 4) if (remainder and (1 shl bit) != 0) remainder = remainder xor (0x13 shl (bit - 4))
        return remainder and 15
    }
}
