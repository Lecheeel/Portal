package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlin.math.pow
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
            assertEquals(3L, decoded.subframe!!.rawUnsigned(15, 3))
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

    @Test fun decodesD1EphemerisOnlyWhenThreeConsistentSubframesAndExternalValidityExist() {
        val subframes = (1..3).map { id ->
            BeiDouNavigationWords.decode(observation(BeiDouNavigationFormat.D1, id, 432000 + (id - 1) * 6)).subframe!!
        }
        val result = BeiDouD1EphemerisDecoder.decode(subframes, BeiDouTime(700, 431000.0), BeiDouTime(700, 433000.0), BeiDouOrbitType.MEO)
        assertNotNull(result.reason, result.decoded)
        val decoded = result.decoded!!
        assertEquals(24, decoded.ephemeris.svid)
        assertEquals(700, decoded.ephemeris.toe.week)
        assertEquals(432000.0, decoded.ephemeris.toe.secondsOfWeek, 0.0)
        assertEquals(432008.0, decoded.ephemeris.toc.secondsOfWeek, 0.0)
        assertEquals(5440.0, decoded.ephemeris.sqrtSemiMajorAxis, 1e-9)
        assertEquals(5000 * 2.0.pow(-33), decoded.ephemeris.eccentricity, 1e-15)
        assertEquals(3 * 2.0.pow(-31), decoded.ephemeris.cucRad, 1e-15)
        assertEquals(3 * 0.1e-9, decoded.tgd2Seconds, 1e-15)
        assertEquals(0, decoded.health)
        assertEquals(5, decoded.uraIndex)
        assertEquals(432000.0, decoded.transmissionTime.secondsOfWeek, 0.0)
        assertNull(BeiDouD1EphemerisDecoder.decode(subframes, BeiDouTime(700, 432001.0),
            BeiDouTime(700, 432002.0), BeiDouOrbitType.MEO).decoded)
        val inconsistent = subframes.toMutableList()
        inconsistent[2] = BeiDouNavigationWords.decode(observation(BeiDouNavigationFormat.D1, 3, 432042)).subframe!!
        assertNull(BeiDouD1EphemerisDecoder.decode(inconsistent, BeiDouTime(700, 431000.0),
            BeiDouTime(700, 433000.0), BeiDouOrbitType.MEO).decoded)
    }

    private fun observation(format: BeiDouNavigationFormat, id: Int = 3, sow: Int = if (format == BeiDouNavigationFormat.D1) 432012 else 432000): GnssNavigationObservation {
        val information = BooleanArray(224)
        fun infoIndex(raw: Int): Int {
            val word = raw / 30; val within = raw % 30
            return if (word == 0) within else 26 + (word - 1) * 22 +
                if (within % 2 == 0) within / 2 else 11 + within / 2
        }
        fun setRaw(start: Int, length: Int, value: Int) = repeat(length) { bit ->
            information[infoIndex(start + bit)] = value ushr (length - bit - 1) and 1 != 0
        }
        fun setParts(a: Int, al: Int, b: Int, bl: Int, value: Int) {
            setRaw(a, al, value ushr bl); setRaw(b, bl, value and ((1 shl bl) - 1))
        }
        setRaw(0, 11, 0b11100010010)
        setRaw(15, 3, id)
        setParts(18, 8, 30, 12, sow)
        setRaw(42, 1, 0); setRaw(48, 4, 5)
        if (id == 1) {
            setRaw(43, 5, 3); setRaw(60, 13, 700); setParts(73, 9, 90, 8, 432008 / 8)
            setRaw(98, 10, 2); setParts(108, 4, 120, 6, 3); setRaw(214, 11, 0)
            setParts(225, 7, 240, 17, 1000); setParts(257, 5, 270, 17, 2000); setRaw(287, 5, 4)
        } else if (id == 2) {
            setParts(42, 10, 60, 6, 2); setParts(66, 16, 90, 2, 3); setParts(92, 20, 120, 12, 4)
            setParts(132, 10, 150, 22, 5000); setRaw(180, 18, 6); setParts(198, 4, 210, 14, 7)
            setParts(224, 8, 240, 10, 8); setParts(250, 12, 270, 20, 5440 * 524288); setRaw(290, 2, 1)
        } else {
            setParts(42, 10, 60, 5, 21232); setParts(65, 17, 90, 15, 515396); setParts(105, 7, 120, 11, 2)
            setParts(131, 11, 150, 13, 3); setParts(163, 9, 180, 9, 4); setParts(189, 13, 210, 1, 5)
            setParts(211, 21, 240, 11, 6); setParts(251, 11, 270, 21, 7)
        }
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
            24, 1, id, id, Base64.getEncoder().encodeToString(bytes))
    }

    private fun bch(data: Int): Int {
        var remainder = data shl 4
        for (bit in 14 downTo 4) if (remainder and (1 shl bit) != 0) remainder = remainder xor (0x13 shl (bit - 4))
        return remainder and 15
    }
}
