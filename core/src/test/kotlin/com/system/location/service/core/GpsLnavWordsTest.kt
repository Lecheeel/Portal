package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GpsLnavWordsTest {
    private val fixtures = Json.parseToJsonElement(javaClass.getResource("/gnss/gps-lnav-words-reference.json")!!
        .readText()).jsonObject.getValue("subframes").jsonArray.map { it.jsonObject }
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun observation(f: JsonObject = fixtures.first(), payload: ByteArray =
        f.getValue("payloadHex").jsonPrimitive.content.hexBytes()) =
        GnssNavigationObservation(1_000, 0x0101, f.int("svid"), 1, -1, f.int("id"),
            Base64.getEncoder().encodeToString(payload))

    @Test fun matchesAllNinetyIndependentlyDecodedWordsIncludingDataInversion() {
        fixtures.forEach { f ->
            val result = GpsLnavWords.decode(observation(f))
            assertNotNull(result.reason, result.subframe)
            val frame = result.subframe!!
            assertEquals(f.int("id"), frame.id)
            assertEquals(f.int("nextTowSeconds"), frame.nextTowSeconds)
            assertFalse(frame.alert)
            val expected = f.getValue("decodedHex").jsonPrimitive.content.hexBytes()
            expected.forEachIndexed { i, b -> assertEquals((b.toInt() and 255).toLong(), frame.unsigned(i * 8, 8)) }
        }
    }

    @Test fun everySingleBitCorruptionInThe300BitSubframeIsRejectedEvenWhenReceiverClaimsPassed() {
        val f = fixtures.first()
        val original = f.getValue("payloadHex").jsonPrimitive.content.hexBytes()
        repeat(10) { word -> repeat(30) { bit ->
            val corrupted = original.copyOf()
            val index = word * 4 + 3 - bit / 8
            corrupted[index] = (corrupted[index].toInt() xor (1 shl (bit % 8))).toByte()
            assertNull("word=$word bit=$bit", GpsLnavWords.decode(observation(f, corrupted)).subframe)
        } }
    }

    @Test fun rejectsPaddingTruncationForeignTypesAndConflictingMetadata() {
        val o = observation()
        val bytes = Base64.getDecoder().decode(o.dataBase64)
        bytes[0] = (bytes[0].toInt() or 0x80).toByte()
        assertNull(GpsLnavWords.decode(observation(payload = bytes)).subframe)
        assertNull(GpsLnavWords.decode(observation(payload = bytes.copyOf(39))).subframe)
        assertNull(GpsLnavWords.decode(o.copy(type = 0x0601)).subframe)
        assertNull(GpsLnavWords.decode(o.copy(svid = 33)).subframe)
        assertNull(GpsLnavWords.decode(o.copy(submessageId = 2)).subframe)
        assertNull(GpsLnavWords.decode(o.copy(status = 4)).subframe)
        // An unknown/rebuilt receiver flag is usable only if the actual parity also passes.
        assertNotNull(GpsLnavWords.decode(o.copy(status = 0)).subframe)
        assertNotNull(GpsLnavWords.decode(o.copy(status = 2)).subframe)
    }

    @Test fun signedFieldsPreserveTwosComplementAcrossWordBoundaries() {
        val frame = GpsLnavWords.decode(observation(fixtures[1])).subframe!!
        assertEquals(-1670L, frame.signed(56, 16)) // Crs = -52.1875 m at 2^-5.
        assertEquals(32850L, frame.unsigned(216, 16)) // toe = 525600 seconds at 2^4.
        val third = GpsLnavWords.decode(observation(fixtures[2])).subframe!!
        assertTrue(third.signed(64, 32) < 0) // Omega0 spans two parity-free words.
        assertThrows(IllegalArgumentException::class.java) { frame.unsigned(239, 2) }
        assertThrows(IllegalArgumentException::class.java) { frame.signed(0, 33) }
    }
}
