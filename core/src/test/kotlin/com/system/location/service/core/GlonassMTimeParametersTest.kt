package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.math.BigInteger
import java.time.LocalDate
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GlonassMTimeParametersTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-time-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.long
    private fun observation(row: JsonObject = records.first()) = GnssNavigationObservation(
        12_345, 0x0301, 7, 1, 3, 5, row.getValue("androidBase64").jsonPrimitive.content)
    private fun string(row: JsonObject = records.first()): GlonassNavigationString {
        val result = GlonassNavigationStrings.decode(observation(row))
        assertNotNull(result.reason, result.string)
        return result.string!!
    }

    @Test fun matchesIndependentCExtractionAtBothSignsAndBroadcastFieldLimits() {
        val dates = listOf("2014-03-10", "2119-12-31", "1996-01-01", "2103-12-31", "1996-12-31")
        records.forEachIndexed { index, row ->
            val result = GlonassMTimeParametersDecoder.decode(string(row), 1)
            assertNotNull(result.reason, result.parameters)
            val p = result.parameters!!
            val expected = row.getValue("expected").jsonObject
            assertEquals(expected.number("n4").toInt(), p.fourYearIntervalNumber)
            assertEquals(expected.number("na").toInt(), p.almanacDayNumber)
            assertEquals(expected.number("tauCRawUnits") * Math.scalb(1.0, -31), p.tauCSeconds, 0.0)
            assertEquals(expected.number("tauGpsRawUnits") * Math.scalb(1.0, -30), p.tauGpsFractionalSeconds, 0.0)
            assertEquals(expected.number("ln").toInt(), p.healthLn)
            assertEquals(LocalDate.parse(dates[index]), p.almanacDayStart.date)
            assertEquals(0.0, p.almanacDayStart.secondsOfDay, 0.0)
            assertEquals(7, p.sourceSlot)
            assertEquals(3, p.frameId)
            assertEquals(12_345L, p.capturedAtMillis)
        }
    }

    @Test fun doesNotGuessSatelliteVariantOrDecodeAnotherStringAsTimeParameters() {
        for (variant in listOf(-1, 0, 2, 3)) {
            assertNull(GlonassMTimeParametersDecoder.decode(string(), variant).parameters)
        }
        val wrong = GlonassNavigationString(7, 12_345, 3, 4, Base64.getDecoder().decode(observation().dataBase64))
        assertNull(GlonassMTimeParametersDecoder.decode(wrong, 1).parameters)
    }

    private fun withFields(vararg fields: Triple<Int, Int, Long>): GlonassNavigationString {
        // Field-layer negative cases only. Wire validation is exercised before decoding C vectors.
        var word = BigInteger(1, Base64.getDecoder().decode(observation().dataBase64))
        fields.forEach { (high, length, value) ->
            val shift = high - length
            val mask = BigInteger.ONE.shiftLeft(length).subtract(BigInteger.ONE).shiftLeft(shift)
            word = word.andNot(mask).or(BigInteger.valueOf(value).shiftLeft(shift))
        }
        val bytes = word.toByteArray()
        val padded = ByteArray(11)
        bytes.copyInto(padded, 11 - bytes.size)
        return GlonassNavigationString(7, 12_345, 3, 5, padded)
    }

    @Test fun rejectsUnknownAndImpossibleCalendarFieldsRatherThanInferringTheCurrentEra() {
        assertNull(GlonassMTimeParametersDecoder.decode(withFields(Triple(36, 5, 0L)), 1).parameters)
        for (na in listOf(0L, 1462L, 2047L)) {
            assertNull(GlonassMTimeParametersDecoder.decode(withFields(Triple(80, 11, na)), 1).parameters)
        }
        // N4=27 starts in 2100, a Gregorian non-leap year. Its interval has only 1460 days.
        assertNull(GlonassMTimeParametersDecoder.decode(withFields(Triple(36, 5, 27L), Triple(80, 11, 1461L)), 1).parameters)
    }

    @Test fun signMagnitudeNegativeZeroDoesNotBecomeAnOffsetAvailabilityClaim() {
        val negativeZeroC = GlonassMTimeParametersDecoder.decode(withFields(Triple(69, 32, 1L shl 31)), 1).parameters!!
        val negativeZeroGps = GlonassMTimeParametersDecoder.decode(withFields(Triple(31, 22, 1L shl 21)), 1).parameters!!
        assertEquals(0.0, negativeZeroC.tauCSeconds, 0.0)
        assertEquals(0.0, negativeZeroGps.tauGpsFractionalSeconds, 0.0)
        // The result exposes raw fractional evidence only; it has no GPS conversion API/default.
        val zero = GlonassMTimeParametersDecoder.decode(string(records.last()), 1).parameters!!
        assertEquals(0.0, zero.tauCSeconds, 0.0)
        assertEquals(0.0, zero.tauGpsFractionalSeconds, 0.0)
    }
}
