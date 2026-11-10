package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeiDouD2EphemerisDecoderTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/beidou-d2-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun pages(index: Int = 0, svid: Int = 59): List<BeiDouNavigationSubframe> =
        records[index].getValue("payloads").jsonArray.mapIndexed { i, payload ->
            val result = BeiDouNavigationWords.decode(GnssNavigationObservation(i * 3000L, 0x0502,
                svid, 1, i + 1, 1, payload.jsonPrimitive.content))
            assertNotNull(result.reason, result.subframe)
            result.subframe!!
        }
    private fun decode(pages: Collection<BeiDouNavigationSubframe>, reference: BeiDouTime = BeiDouTime(700, 432001.0),
        from: BeiDouTime = BeiDouTime(700, 431000.0), until: BeiDouTime = BeiDouTime(700, 433000.0)) =
        BeiDouD2EphemerisDecoder.decode(pages, from, until, reference)

    @Test fun matchesIndependentCFieldsWithBothSignsAndPreservesModernGeoIdentity() {
        records.forEachIndexed { index, record ->
            val result = decode(pages(index).reversed())
            assertNotNull(result.reason, result.decoded)
            val decoded = result.decoded!!
            val e = decoded.ephemeris
            val expected = record.getValue("expected").jsonObject
            fun n(name: String) = expected.getValue(name).jsonPrimitive.double
            val actual = mapOf("sqrtA" to e.sqrtSemiMajorAxis, "e" to e.eccentricity,
                "i0" to e.inclinationRad, "omega0" to e.ascendingNodeRad, "omega" to e.argumentOfPerigeeRad,
                "m0" to e.meanAnomalyRad, "deltaN" to e.deltaMeanMotionRadPerSecond,
                "omegaDot" to e.ascendingNodeRateRadPerSecond, "iDot" to e.inclinationRateRadPerSecond,
                "crc" to e.crcMeters, "crs" to e.crsMeters, "cuc" to e.cucRad, "cus" to e.cusRad,
                "cic" to e.cicRad, "cis" to e.cisRad, "af0" to e.af0Seconds,
                "af1" to e.af1SecondsPerSecond, "af2" to e.af2SecondsPerSecondSquared,
                "tgd1" to decoded.tgd1Seconds, "tgd2" to decoded.tgd2Seconds)
            actual.forEach { (name, value) -> assertEquals(name, n(name), value, Math.ulp(n(name)) * 4) }
            assertEquals(n("week").toInt(), e.toe.week)
            assertEquals(n("toe"), e.toe.secondsOfWeek, 0.0)
            assertEquals(n("health").toInt(), decoded.health)
            assertEquals(n("ura").toInt(), decoded.uraIndex)
            assertEquals(n("aodc").toInt(), decoded.ageOfDataClock)
            assertEquals(n("aode").toInt(), decoded.ageOfDataEphemeris)
            assertEquals(59, e.svid)
            assertEquals(BeiDouOrbitType.GEO, e.orbitType)
            assertEquals(decoded, decode(pages(index).filterIndexed { i, _ -> i != 1 }).decoded)
        }
    }

    @Test fun requiresEveryEphemerisPageAndRejectsDuplicatesMixedIdentitiesAndTransmissionCycles() {
        val pages = pages()
        for (index in 0..9) {
            val result = decode(pages.filterIndexed { i, _ -> i != index })
            if (index == 1) assertNotNull(result.reason, result.decoded) else assertNull(result.decoded)
        }
        assertNull(decode(pages + pages[0]).decoded)
        assertNull(decode(pages.dropLast(1) + pages(svid = 1).last()).decoded)
        assertNull(decode(pages.dropLast(1) + edited(pages.last(), sow = 432030)).decoded)
        assertNull(decode(pages.dropLast(1) + edited(pages.last(), captured = 60_001)).decoded)
        assertNull(decode(pages, reference = BeiDouTime(701, 432001.0)).decoded)
        assertNull(decode(pages, until = BeiDouTime(700, 432026.0)).decoded)
        assertNull(decode(pages, from = BeiDouTime(700, 432001.0)).decoded)
        assertNull(decode(pages, until = BeiDouTime(700, 430999.0)).decoded)
        assertNull(decode(pages.dropLast(1) + edited(pages.last(), fieldStart = 42, fieldLength = 4, value = 11)).decoded)
    }

    @Test fun clockEpochIsIndependentAndWeeklessEpochsResolveAroundTransmission() {
        val pages = pages().toMutableList()
        pages[0] = edited(pages[0], fieldStart = 90, fieldLength = 12, value = (54000 and 4095) + 1L)
        val decoded = decode(pages).decoded!!
        assertEquals(432008.0, decoded.ephemeris.toc.secondsOfWeek, 0.0)
        assertEquals(432000.0, decoded.ephemeris.toe.secondsOfWeek, 0.0)
        val nextWeek = pages().toMutableList()
        nextWeek[6] = edited(edited(nextWeek[6], fieldStart = 80, fieldLength = 2, value = 0),
            fieldStart = 90, fieldLength = 15, value = 0)
        assertEquals(BeiDouTime(701, 0.0), decode(nextWeek).decoded!!.ephemeris.toe)
        val badToc = pages().toMutableList()
        badToc[0] = edited(edited(badToc[0], fieldStart = 77, fieldLength = 5, value = 31),
            fieldStart = 90, fieldLength = 12, value = 4095)
        assertNull(decode(badToc).decoded)
    }

    /** Decoder-level field injection after the separately tested transport/BCH layer. */
    private fun edited(page: BeiDouNavigationSubframe, sow: Int = page.secondsOfWeek,
        captured: Long = page.capturedAtMillis, fieldStart: Int = 0, fieldLength: Int = 0, value: Long = 0): BeiDouNavigationSubframe {
        val information = ByteArray(28) { page.unsigned(it * 8, 8).toByte() }
        repeat(fieldLength) { offset ->
            val raw = fieldStart + offset
            val word = raw / 30
            require(raw % 30 < if (word == 0) 26 else 22)
            val index = if (word == 0) raw else 26 + (word - 1) * 22 + raw % 30
            val mask = 1 shl (7 - index % 8)
            information[index / 8] = ((information[index / 8].toInt() and mask.inv()) or
                (if (value ushr (fieldLength - 1 - offset) and 1L != 0L) mask else 0)).toByte()
        }
        return BeiDouNavigationSubframe(page.svid, captured, page.format, page.id, sow, information)
    }
}
