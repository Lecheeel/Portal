package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class GlonassNavigationStringsTest {
    private val strings = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-strings-reference.json")!!.readText())
        .jsonObject.getValue("strings").jsonArray.map { it.jsonObject }
    private fun observation(r: JsonObject = strings.first()) = GnssNavigationObservation(1, 0x0301, 7, 1, 3,
        r.getValue("stringId").jsonPrimitive.int, r.getValue("androidBase64").jsonPrimitive.content)

    @Test fun allFifteenStringsMatchIndependentCFieldExtractionAndHammingVectors() {
        strings.forEach { r ->
            val expected = r.getValue("expected").jsonObject
            val result = GlonassNavigationStrings.decode(observation(r))
            assertNotNull(result.reason, result.string)
            val s = result.string!!
            assertEquals(expected.getValue("stringId").jsonPrimitive.int, s.stringId)
            assertEquals(7, s.slot)
            assertEquals(3, s.frameId)
            assertEquals(expected.getValue("signed64_24").jsonPrimitive.long, s.signMagnitude(64, 24))
            assertEquals(expected.getValue("signed40_5").jsonPrimitive.long, s.signMagnitude(40, 5))
            assertEquals(expected.getValue("signed35_27").jsonPrimitive.long, s.signMagnitude(35, 27))
            assertEquals(expected.getValue("unsigned80_22").jsonPrimitive.long, s.unsigned(80, 22))
        }
    }

    @Test fun everySingleBitErrorIsRejectedEvenWhenHalClaimsParityPassedOrRebuilt() {
        strings.forEach { r ->
            val original = observation(r)
            val payload = Base64.getDecoder().decode(original.dataBase64)
            // All 85 transmitted bits, including idle/checksum, plus three padding bits.
            for (index in 0 until 88) {
                val bad = payload.copyOf()
                bad[index / 8] = (bad[index / 8].toInt() xor (1 shl (7 - index % 8))).toByte()
                for (status in 1..2) {
                    val result = GlonassNavigationStrings.decode(original.copy(status = status,
                        dataBase64 = Base64.getEncoder().encodeToString(bad)))
                    assertNull("accepted string ${original.submessageId}, bit $index", result.string)
                }
            }
        }
    }

    @Test fun rejectsTwoBitErrorsMetadataAndWrongAlignmentWithoutReadingParityAsInformation() {
        val o = observation()
        val payload = Base64.getDecoder().decode(o.dataBase64)
        // A two-bit corruption cannot be repaired by trusting the HAL status.
        for (first in 3 until 88) for (second in first + 1 until 88) {
            val bad = payload.copyOf()
            for (index in listOf(first, second))
                bad[index / 8] = (bad[index / 8].toInt() xor (1 shl (7 - index % 8))).toByte()
            assertNull(GlonassNavigationStrings.decode(o.copy(dataBase64 = Base64.getEncoder().encodeToString(bad))).string)
        }
        listOf(o.copy(type = 0x0101), o.copy(svid = 93), o.copy(status = 3), o.copy(messageId = 0),
            o.copy(messageId = 6), o.copy(submessageId = 2),
            o.copy(dataBase64 = Base64.getEncoder().encodeToString(payload.copyOf(10)))).forEach {
            assertNull(GlonassNavigationStrings.decode(it).string)
        }
        val leftAligned = java.math.BigInteger(1, payload).shiftLeft(3).toByteArray().takeLast(11).toByteArray()
        assertNull(GlonassNavigationStrings.decode(o.copy(dataBase64 = Base64.getEncoder().encodeToString(leftAligned))).string)
        val s = GlonassNavigationStrings.decode(o).string!!
        assertThrows(IllegalArgumentException::class.java) { s.unsigned(85, 1) }
        assertThrows(IllegalArgumentException::class.java) { s.unsigned(10, 3) }
        assertThrows(IllegalArgumentException::class.java) { s.unsigned(84, 33) }
    }
}
