package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoGpsOffsetDecoderTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-ggto-reference.json")!!
        .readText()).jsonObject.getValue("vectors").jsonArray.map { it.jsonObject }
    private val from = GalileoTime(1080, 0.0)
    private val until = GalileoTime(1083, 0.0)
    private val gpsFrom = GpsTime(2105, 432729.9)
    private val gpsUntil = GpsTime(2105, 432730.1)
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun page(vector: JsonObject): GalileoNavigationPage {
        val bytes = vector.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val result = GalileoNavigationPages.decode(GnssNavigationObservation(1000, vector.int("type"), 24, 1,
            -1, vector.int("wordType"), Base64.getEncoder().encodeToString(bytes)))
        assertNotNull(result.reason, result.page)
        return result.page!!
    }
    private fun base(inav: Boolean = true, name: String = "zero") = page(vectors.first {
        it.getValue("name").jsonPrimitive.content == name && it.int("type") == if (inav) 0x0601 else 0x0602
    })
    /** Alter an already-unpacked information block to isolate decoder semantics from CRC tests. */
    private fun altered(page: GalileoNavigationPage, vararg fields: Triple<Int, Int, Long>): GalileoNavigationPage {
        val length = if (page.format == GalileoClockReference.INAV_E1_E5B) 128 else 214
        val bytes = ByteArray((length + 7) / 8)
        repeat(length) { bit -> bytes[bit / 8] = (bytes[bit / 8].toInt() or
            (page.unsigned(bit, 1).toInt() shl (7 - bit % 8))).toByte() }
        fields.forEach { (start, size, value) -> repeat(size) { bit ->
            val position = start + bit; val mask = 1 shl (7 - position % 8)
            bytes[position / 8] = ((bytes[position / 8].toInt() and mask.inv()) or
                (if (value ushr (size - bit - 1) and 1 == 1L) mask else 0)).toByte()
        } }
        return GalileoNavigationPage(page.svid, page.capturedAtMillis, page.format, page.wordType, bytes, length)
    }
    private fun decode(page: GalileoNavigationPage) = GalileoGpsOffsetDecoder.decode(page, gpsFrom, gpsUntil, from, until)

    @Test fun bothLayoutsMatchIndependentlyCCheckedSignedFieldPatternsAndCrcs() {
        val referenceWeeks = mapOf("zero" to 1081, "negative" to 1050, "positive" to 1112, "individual-ones" to 1087)
        vectors.forEach { vector ->
            val name = vector.getValue("name").jsonPrimitive.content
            val result = decode(page(vector))
            if (name == "unavailable") {
                assertNull(result.decoded)
                assertTrue(result.reason!!.contains("unavailable"))
            } else {
                assertNotNull(result.reason, result.decoded)
                val d = result.decoded!!
                assertEquals(referenceWeeks.getValue(name), d.offset.referenceWeek)
                assertEquals(vector.int("a0Units"), d.offset.a0Units)
                assertEquals(vector.int("a1Units"), d.offset.a1Units)
                assertEquals(vector.int("referenceHour"), d.offset.referenceHour)
                if (vector.int("type") == 0x0601) assertNull(d.transmissionTime)
                else assertEquals(GalileoTime(1081, 432730.0), d.transmissionTime)
            }
        }
    }

    @Test fun eachSideOf31WeekResolutionIsAcceptedButTheHalfCycleIsNotGuessed() {
        for (inav in listOf(true, false)) {
            val weekStart = if (inav) 122 else 183
            for (week in listOf(1050, 1112)) {
                val p = altered(base(inav), Triple(weekStart, 6, (week % 64).toLong()))
                assertEquals(week, decode(p).decoded!!.offset.referenceWeek)
            }
            val ambiguousHalfCycle = altered(base(inav), Triple(weekStart, 6, 25L))
            assertNull(decode(ambiguousHalfCycle).decoded) // Both full candidates differ by 32 weeks.
        }
    }

    @Test fun referenceWeekRuleUsesTheBroadcastWindowAcrossRolloverRatherThanCallbackWeek() {
        for (inav in listOf(true, false)) {
            val p = if (inav) altered(base(), Triple(122, 6, 26L)) else
                altered(base(false), Triple(183, 6, 26L), Triple(189, 20, 604799L))
            val result = GalileoGpsOffsetDecoder.decode(p,
                GpsTime(2105, 604798.9), GpsTime(2106, 0.1), from, until)
            assertNotNull(result.reason, result.decoded)
            val decoded = result.decoded!!
            assertEquals(1050, decoded.offset.referenceWeek)
            if (!inav) assertEquals(GalileoTime(1081, 604799.0), decoded.transmissionTime)
        }
    }

    @Test fun bootstrapNearWeekBoundaryAppliesGgtoBeforeCheckingTheTruncatedWeek() {
        val negative = altered(base(), Triple(86, 16, -32768L), Triple(122, 6, 32L))
        val positive = altered(base(), Triple(86, 16, 32767L), Triple(122, 6, 32L))
        val before = GpsTime(2112, 0.0)
        val after = GpsTime(2112, 604799.9999999)
        val validFrom = GalileoTime(1086, 0.0); val validUntil = GalileoTime(1091, 0.0)
        val a = GalileoGpsOffsetDecoder.decode(negative, before, before, validFrom, validUntil).decoded!!
        val b = GalileoGpsOffsetDecoder.decode(positive, after, after, validFrom, validUntil).decoded!!
        assertEquals(1056, a.offset.referenceWeek)
        assertEquals(1120, b.offset.referenceWeek)
        assertEquals(1087, a.offset.gpsToGalileo(before).time!!.week)
        assertEquals(1089, b.offset.gpsToGalileo(after).time!!.week)
    }

    @Test fun fullReferenceWeekResolvesAcrossThe4096WeekGstEra() {
        for (inav in listOf(true, false)) {
            val p = if (inav) altered(base(), Triple(122, 6, 63L)) else
                altered(base(false), Triple(183, 6, 63L), Triple(189, 20, 100L))
            val result = GalileoGpsOffsetDecoder.decode(p, GpsTime(5120, 99.9), GpsTime(5120, 100.1),
                GalileoTime(4095, 0.0), GalileoTime(4097, 0.0))
            val decoded = result.decoded!!
            assertEquals(4095, decoded.offset.referenceWeek)
            if (!inav) assertEquals(GalileoTime(4096, 100.0), decoded.transmissionTime)
        }
    }

    @Test fun fnavTowMustBeInsideTheSuppliedIntervalWhileInavCannotInventOne() {
        val lateFrom = GpsTime(2105, 432731.0); val lateUntil = GpsTime(2105, 432732.0)
        assertNull(GalileoGpsOffsetDecoder.decode(base(false), lateFrom, lateUntil, from, until).decoded)
        assertNull(decode(altered(base(false), Triple(189, 20, 604800L))).decoded)
        val inav = GalileoGpsOffsetDecoder.decode(base(), lateFrom, lateUntil, from, until).decoded!!
        assertNull(inav.transmissionTime)
    }

    @Test fun combinedSentinelIsRejectedWithoutRejectingIndividualAllOneFields() {
        for (inav in listOf(true, false)) {
            val unavailable = base(inav, "unavailable")
            assertNull(decode(unavailable).decoded)
            val valid = altered(unavailable, Triple(if (inav) 122 else 183, 6, 62L))
            assertEquals(1086, decode(valid).decoded!!.offset.referenceWeek)
        }
    }

    @Test fun associationAndValidityEvidenceAreRequiredAndNoUtcOrEraFallbackExists() {
        val p = base()
        assertNull(GalileoGpsOffsetDecoder.decode(p, gpsUntil, gpsFrom, from, until).decoded)
        assertNull(GalileoGpsOffsetDecoder.decode(p, gpsFrom, gpsFrom.plusSeconds(120.1), from, until).decoded)
        assertNull(GalileoGpsOffsetDecoder.decode(p, gpsFrom, gpsUntil, until, from).decoded)
        assertNull(GalileoGpsOffsetDecoder.decode(p, gpsFrom, gpsUntil, from, GalileoTime(1081, 432700.0)).decoded)
        assertNull(GalileoGpsOffsetDecoder.decode(p, GpsTime(0, 0.0), GpsTime(0, 1.0), from, until).decoded)
    }

    @Test fun ordinaryNavigationPagesCannotSupplyGgtoEvenWithAValidCrc() {
        val ordinary = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-pages-reference.json")!!
            .readText()).jsonObject.getValue("pages").jsonArray.map { it.jsonObject }
        ordinary.filter { it.int("wordType") == 1 }.forEach { assertNull(decode(page(it)).decoded) }
    }
}
