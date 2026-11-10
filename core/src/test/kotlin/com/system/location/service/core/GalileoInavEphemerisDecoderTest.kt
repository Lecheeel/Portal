package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoInavEphemerisDecoderTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-pages-reference.json")!!
        .readText()).jsonObject
    private val pages = fixture.getValue("pages").jsonArray.map { it.jsonObject }
        .filter { it.getValue("type").jsonPrimitive.int == 0x0601 && it.getValue("wordType").jsonPrimitive.int in 1..5 }
        .map { page ->
            val type = page.getValue("wordType").jsonPrimitive.int
            val bytes = page.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            GalileoNavigationPages.decode(GnssNavigationObservation(type * 2000L, 0x0601, 24, 1, -1,
                type, Base64.getEncoder().encodeToString(bytes))).page!!
        }
    private val ref = GalileoTime(1081, 432704.0)
    private val from = GalileoTime(1081, 424800.0)
    private val until = GalileoTime(1081, 439200.0)
    private fun decode(p: List<GalileoNavigationPage> = pages) = GalileoInavEphemerisDecoder.decode(p, ref, from, until)
    private fun altered(page: GalileoNavigationPage, start: Int, length: Int, value: Long,
        svid: Int = page.svid): GalileoNavigationPage {
        val bytes = ByteArray(16) { page.unsigned(it * 8, 8).toByte() }
        repeat(length) { i ->
            val position = start + i
            val mask = 1 shl (7 - position % 8)
            bytes[position / 8] = ((bytes[position / 8].toInt() and mask.inv()) or
                (if ((value ushr (length - i - 1)) and 1 == 1L) mask else 0)).toByte()
        }
        return GalileoNavigationPage(svid, page.capturedAtMillis, page.format, page.wordType, bytes, 128)
    }

    @Test fun completeDecodedInavMatchesEveryIndependentPinnedRtkLibParameter() {
        val result = decode(pages.reversed())
        assertNotNull(result.reason, result.decoded)
        val decoded = result.decoded!!
        val e = decoded.ephemeris
        val expected = fixture.getValue("decodedInav").jsonObject
        fun n(name: String) = expected.getValue(name).jsonPrimitive.double
        assertEquals(n("rinexGpsWeek").toInt() - 1024, e.toe.week)
        assertEquals(n("toe"), e.toe.secondsOfWeek, 0.0)
        assertEquals(n("toc"), e.toc.secondsOfWeek, 0.0)
        assertEquals(n("iodnav").toInt(), e.iodnav)
        assertEquals(n("sisa").toInt(), decoded.sisaIndex)
        val values = mapOf("sqrtA" to e.sqrtSemiMajorAxis, "e" to e.eccentricity, "i0" to e.inclinationRad,
            "omega0" to e.ascendingNodeRad, "omega" to e.argumentOfPerigeeRad, "m0" to e.meanAnomalyRad,
            "deltaN" to e.deltaMeanMotionRadPerSecond, "omegaDot" to e.ascendingNodeRateRadPerSecond,
            "iDot" to e.inclinationRateRadPerSecond, "crc" to e.crcMeters, "crs" to e.crsMeters,
            "cuc" to e.cucRad, "cus" to e.cusRad, "cic" to e.cicRad, "cis" to e.cisRad,
            "af0" to e.af0Seconds, "af1" to e.af1SecondsPerSecond, "af2" to e.af2SecondsPerSecondSquared,
            "bgdE1E5a" to decoded.bgdE1E5aSeconds, "bgdE1E5b" to decoded.bgdE1E5bSeconds)
        values.forEach { (name, value) -> assertEquals(name, n(name), value, Math.ulp(n(name)) * 4) }
        assertEquals(ref, decoded.transmissionTimeOfWordFive)
        assertTrue(decoded.e1Status.usable)
        assertTrue(decoded.e5bStatus.usable)
        assertNotNull(GalileoBroadcastPropagator.propagate(e, ref).state)
    }

    @Test fun mixedIodSatelliteMissingPagesAndNapaCannotBecomeACompleteSet() {
        val invalid = listOf(pages.take(4), pages.take(4) + pages[3],
            pages.mapIndexed { i, p -> if (i == 1) altered(p, 6, 10, 81) else p },
            pages.mapIndexed { i, p -> if (i == 3) altered(p, 16, 6, 23) else p },
            pages.mapIndexed { i, p -> if (i == 2) altered(p, 120, 8, 255) else p },
            pages.mapIndexed { i, p -> if (i == 2) altered(p, 120, 8, 126) else p },
            pages.mapIndexed { i, p -> if (i == 1) altered(p, 0, 1, 0, svid = 25) else p })
        invalid.forEach { assertNull(decode(it).decoded) }
    }

    @Test fun signalHealthAndDvsAreIndependentPerSignalAndAreNotIodBound() {
        val e1Bad = pages.dropLast(1) + altered(pages.last(), 69, 2, 1)
        val a = decode(e1Bad).decoded!!
        assertFalse(a.e1Status.usable)
        assertTrue(a.e5bStatus.usable)
        assertEquals(decode().decoded!!.ephemeris, a.ephemeris)
        val e5bBad = pages.dropLast(1) + altered(pages.last(), 71, 1, 1)
        val b = decode(e5bBad).decoded!!
        assertTrue(b.e1Status.usable)
        assertFalse(b.e5bStatus.usable)
        for (shs in 0..3) for (dvs in 0..1) assertEquals(shs == 0 && dvs == 0, GalileoSignalStatus(shs, dvs).usable)
    }

    @Test fun fullGstEraAndExplicitValidityAreRequiredWithoutGpsUtcGuessing() {
        assertNull(GalileoInavEphemerisDecoder.decode(pages, ref.plusSeconds(121.0), from, until).decoded)
        assertNull(GalileoInavEphemerisDecoder.decode(pages, GalileoTime(1082, ref.secondsOfWeek), from, until).decoded)
        assertNull(GalileoInavEphemerisDecoder.decode(pages, ref, ref.plusSeconds(1.0), until).decoded)
        assertNull(GalileoInavEphemerisDecoder.decode(pages, ref, from, ref.plusSeconds(-1.0)).decoded)
        // Unlike a truncated-week wrap, an explicitly supplied next era must be retained.
        val nextEra = GalileoInavEphemerisDecoder.decode(pages, GalileoTime(ref.week + 4096, ref.secondsOfWeek),
            GalileoTime(from.week + 4096, from.secondsOfWeek), GalileoTime(until.week + 4096, until.secondsOfWeek)).decoded!!
        assertEquals(ref.week + 4096, nextEra.ephemeris.toe.week)
    }
}
