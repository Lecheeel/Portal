package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.math.BigInteger
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GlonassAlmanacHealthTest {
    private val rows = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-almanac-health-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun string(row: JsonObject = rows.first()): GlonassNavigationString {
        val result = GlonassNavigationStrings.decode(GnssNavigationObservation(12_345, 0x0301, 7, 1,
            row.int("frameId"), row.int("stringId"), row.getValue("androidBase64").jsonPrimitive.content))
        assertNotNull(result.reason, result.string)
        return result.string!!
    }

    @Test fun independentCFieldsKeepHealthPolarityAndTheAlmanacTargetDistinctFromTheSender() {
        rows.forEach { row ->
            val result = GlonassAlmanacHealthDecoder.decode(string(row))
            assertNotNull(result.reason, result.health)
            val health = result.health!!
            assertEquals(7, health.sourceSlot)
            assertEquals(row.int("targetSlot"), health.targetSlot)
            assertEquals(row.int("satelliteTypeBits"), health.satelliteTypeBits)
            assertEquals(row.int("healthCn"), health.healthCn)
            assertEquals(row.int("frameId"), health.frameId)
            assertEquals(row.int("stringId"), health.stringId)
            assertEquals(12_345L, health.capturedAtMillis)
        }
        assertEquals(24, GlonassAlmanacHealthDecoder.decode(string()).health!!.targetSlot)
        assertEquals(1, GlonassAlmanacHealthDecoder.decode(string()).health!!.healthCn)
        assertEquals(0, GlonassAlmanacHealthDecoder.decode(string(rows[1])).health!!.healthCn)
    }

    @Test fun excludesImmediateOddAndSpecialUt1StringsBeforeInterpretingTheirBitsAsHealth() {
        val bytes = Base64.getDecoder().decode(rows.first().getValue("androidBase64").jsonPrimitive.content)
        for (id in 1..15) {
            if (id in setOf(6, 8, 10, 12, 14)) continue
            assertNull(GlonassAlmanacHealthDecoder.decode(GlonassNavigationString(7, 12_345, 1, id, bytes)).health)
        }
        // Frame 5 string 14 (superframe string 74) contains B1/B2/KP. Its bits may
        // accidentally look like a valid healthy target and must still be excluded.
        assertNull(GlonassAlmanacHealthDecoder.decode(GlonassNavigationString(7, 12_345, 5, 14, bytes)).health)
    }

    @Test fun rejectsReservedAddressAndSatelliteTypeFieldsWithoutConstructingAReplacementTarget() {
        fun altered(high: Int, length: Int, value: Int): GlonassNavigationString {
            val original = BigInteger(1, Base64.getDecoder().decode(rows.first().getValue("androidBase64").jsonPrimitive.content))
            val shift = high - length
            val mask = BigInteger.ONE.shiftLeft(length).subtract(BigInteger.ONE).shiftLeft(shift)
            val encoded = original.andNot(mask).or(BigInteger.valueOf(value.toLong()).shiftLeft(shift)).toByteArray()
            val bytes = ByteArray(11)
            encoded.copyInto(bytes, 11 - encoded.size)
            return GlonassNavigationString(7, 12_345, 1, 6, bytes)
        }
        for (target in listOf(0, 25, 31)) {
            assertNull(GlonassAlmanacHealthDecoder.decode(altered(77, 5, target)).health)
        }
        for (type in listOf(2, 3)) {
            assertNull(GlonassAlmanacHealthDecoder.decode(altered(79, 2, type)).health)
        }
    }
}
