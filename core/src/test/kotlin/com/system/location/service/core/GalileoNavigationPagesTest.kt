package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoNavigationPagesTest {
    private val pages = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-pages-reference.json")!!
        .readText()).jsonObject.getValue("pages").jsonArray.map { it.jsonObject }
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun observation(page: JsonObject, data: ByteArray = page.getValue("payloadHex").jsonPrimitive.content.hexBytes()) =
        GnssNavigationObservation(1_000, page.int("type"), page.int("svid"), 1, -1, page.int("wordType"),
            Base64.getEncoder().encodeToString(data))

    @Test fun reconstructsAllInformationBitsFromTenIndependentlyCrcCheckedAndroidPages() {
        pages.forEach { expected ->
            val result = GalileoNavigationPages.decode(observation(expected))
            assertNotNull(result.reason, result.page)
            val page = result.page!!
            assertEquals(expected.int("wordType"), page.wordType)
            assertEquals(if (expected.int("type") == 0x0601) GalileoClockReference.INAV_E1_E5B else
                GalileoClockReference.FNAV_E1_E5A, page.format)
            val data = expected.getValue("dataHex").jsonPrimitive.content.hexBytes()
            val length = if (expected.int("type") == 0x0601) 128 else 214
            repeat(length) { bit -> assertEquals(((data[bit / 8].toInt() ushr (7 - bit % 8)) and 1).toLong(), page.unsigned(bit, 1)) }
        }
    }

    @Test fun everyProtectedOrCrcBitCorruptionIsRejectedInBothPageFormats() {
        for (index in listOf(1, 6)) {
            val p = pages[index]
            val bytes = p.getValue("payloadHex").jsonPrimitive.content.hexBytes()
            val protectedAndCrc = if (p.int("type") == 0x0601) 220 else 238
            repeat(protectedAndCrc) { bit ->
                val corrupted = bytes.copyOf()
                corrupted[bit / 8] = (corrupted[bit / 8].toInt() xor (1 shl (7 - bit % 8))).toByte()
                assertNull("type=${p.int("type")} bit=$bit", GalileoNavigationPages.decode(observation(p, corrupted)).page)
            }
        }
    }

    @Test fun unprotectedSspDoesNotInvalidateCrcOrChangeNavigationData() {
        val p = pages[1]
        val bytes = p.getValue("payloadHex").jsonPrimitive.content.hexBytes()
        val expected = GalileoNavigationPages.decode(observation(p)).page!!
        for (bit in 220..227) {
            val altered = bytes.copyOf()
            altered[bit / 8] = (altered[bit / 8].toInt() xor (1 shl (7 - bit % 8))).toByte()
            val decoded = GalileoNavigationPages.decode(observation(p, altered)).page!!
            repeat(4) { assertEquals(expected.unsigned(it * 32, 32), decoded.unsigned(it * 32, 32)) }
        }
    }

    @Test fun paddingLengthConstellationAndMessageIdentityMustMatchThePayload() {
        pages.forEach { p ->
            val bytes = p.getValue("payloadHex").jsonPrimitive.content.hexBytes()
            val padding = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() or 1).toByte() }
            assertNull(GalileoNavigationPages.decode(observation(p, padding)).page)
            assertNull(GalileoNavigationPages.decode(observation(p, bytes.copyOf(bytes.size - 1))).page)
            val message = observation(p)
            assertNull(GalileoNavigationPages.decode(message.copy(svid = 37)).page)
            assertNull(GalileoNavigationPages.decode(message.copy(type = 0x0101)).page)
            assertNull(GalileoNavigationPages.decode(message.copy(submessageId = 62)).page)
            assertNotNull(GalileoNavigationPages.decode(message.copy(status = 0)).page)
            assertNotNull(GalileoNavigationPages.decode(message.copy(status = 2)).page)
        }
    }
}
