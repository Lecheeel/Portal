package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoFnavEphemerisDecoderTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/gnss/$name")!!.readText()).jsonObject
    private val pages = resource("galileo-pages-reference.json").getValue("pages").jsonArray.map { it.jsonObject }
        .filter { it.getValue("type").jsonPrimitive.int == 0x0602 }.map { page ->
            val type = page.getValue("wordType").jsonPrimitive.int
            val bytes = page.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            GalileoNavigationPages.decode(GnssNavigationObservation(type * 10000L, 0x0602, 24, 1, -1,
                type, Base64.getEncoder().encodeToString(bytes))).page!!
        }
    private val ref = GalileoTime(1081, 432700.0)
    private val from = GalileoTime(1081, 424800.0)
    private val until = GalileoTime(1081, 439200.0)
    private fun decode(p: List<GalileoNavigationPage> = pages) = GalileoFnavEphemerisDecoder.decode(p, ref, from, until)

    @Test fun defaultsToStandardForwardAgeAndExternalLimitsCannotExtendIt() {
        val e = GalileoFnavEphemerisDecoder.decode(pages, ref).decoded!!.ephemeris
        assertEquals(e.toe, e.validFrom)
        assertEquals(e.toe.plusSeconds(14400.0), e.validUntil)
        val extended = GalileoFnavEphemerisDecoder.decode(pages, ref, e.toe.plusSeconds(-100.0),
            e.toe.plusSeconds(100000.0)).decoded!!.ephemeris
        assertEquals(e, extended)
        // Move toe to five hours before the unchanged, independently checked page times.
        assertNull(GalileoFnavEphemerisDecoder.decode(altered(2, 160, 14, 6900), ref).decoded)
    }
    private fun altered(index: Int, start: Int, length: Int, value: Long): List<GalileoNavigationPage> = pages.mapIndexed { i, p ->
        if (i != index) p else {
            val bytes = ByteArray(27)
            repeat(214) { bit -> bytes[bit / 8] = (bytes[bit / 8].toInt() or
                (p.unsigned(bit, 1).toInt() shl (7 - bit % 8))).toByte() }
            repeat(length) { bit ->
                val pos = start + bit; val mask = 1 shl (7 - pos % 8)
                bytes[pos / 8] = ((bytes[pos / 8].toInt() and mask.inv()) or
                    (if (value ushr (length - bit - 1) and 1 == 1L) mask else 0)).toByte()
            }
            GalileoNavigationPage(p.svid, p.capturedAtMillis, p.format, p.wordType, bytes, 214)
        }
    }

    @Test fun parametersRoundTripActualFnavRinexWithinBroadcastQuantizationAndClockModel() {
        val result = decode(pages.reversed())
        assertNotNull(result.reason, result.decoded)
        val decoded = result.decoded!!
        val e = decoded.ephemeris
        val r = resource("galileo-reference.json").getValue("records").jsonArray.first().jsonObject
        fun n(name: String) = r.getValue(name).jsonPrimitive.double
        assertEquals(24, e.svid)
        assertEquals(80, e.iodnav)
        assertEquals(GalileoTime(1081, 432000.0), e.toe)
        assertEquals(e.toe, e.toc)
        assertEquals(GalileoClockReference.FNAV_E1_E5A, e.clockReference)
        assertEquals(107, decoded.sisaIndex) // 3.12m in the source RINEX (ICD Table 91).
        assertTrue(decoded.e5aStatus.usable)
        val values = listOf(
            Triple("sqrtA", e.sqrtSemiMajorAxis, Math.scalb(1.0, -19)), Triple("e", e.eccentricity, Math.scalb(1.0, -33)),
            Triple("i0", e.inclinationRad, Math.scalb(3.1415926535898, -31)),
            Triple("omega0", e.ascendingNodeRad, Math.scalb(3.1415926535898, -31)),
            Triple("omega", e.argumentOfPerigeeRad, Math.scalb(3.1415926535898, -31)),
            Triple("m0", e.meanAnomalyRad, Math.scalb(3.1415926535898, -31)),
            Triple("deltaN", e.deltaMeanMotionRadPerSecond, Math.scalb(3.1415926535898, -43)),
            Triple("omegaDot", e.ascendingNodeRateRadPerSecond, Math.scalb(3.1415926535898, -43)),
            Triple("iDot", e.inclinationRateRadPerSecond, Math.scalb(3.1415926535898, -43)),
            Triple("cuc", e.cucRad, Math.scalb(1.0, -29)), Triple("cus", e.cusRad, Math.scalb(1.0, -29)),
            Triple("cic", e.cicRad, Math.scalb(1.0, -29)), Triple("cis", e.cisRad, Math.scalb(1.0, -29)),
            Triple("crc", e.crcMeters, Math.scalb(1.0, -5)), Triple("crs", e.crsMeters, Math.scalb(1.0, -5)),
            Triple("af0", e.af0Seconds, Math.scalb(1.0, -34)), Triple("af1", e.af1SecondsPerSecond, Math.scalb(1.0, -46)),
            Triple("af2", e.af2SecondsPerSecondSquared, Math.scalb(1.0, -59)),
            Triple("bgdE1E5a", decoded.bgdE1E5aSeconds, Math.scalb(1.0, -32)))
        values.forEach { (name, value, lsb) -> assertEquals(name, n(name), value, lsb / 2 + Math.ulp(n(name)) * 10) }
    }

    @Test fun missingMismatchedOrAmbiguousPageEvidenceCannotFormASet() {
        assertNull(decode(pages.take(3)).decoded)
        assertNull(decode(pages.take(3) + pages[2]).decoded)
        assertNull(decode(altered(1, 6, 10, 81)).decoded)
        assertNull(decode(altered(0, 6, 6, 23)).decoded)
        assertNull(decode(altered(0, 94, 8, 255)).decoded)
        assertNull(decode(altered(1, 182, 12, 1082)).decoded)
        assertNull(decode(altered(2, 186, 20, 604800)).decoded)
        assertNull(decode(altered(3, 189, 20, 450000)).decoded)
        assertNull(decode(altered(2, 160, 14, 16383)).decoded)
        assertNull(GalileoFnavEphemerisDecoder.decode(pages, ref.plusSeconds(121.0), from, until).decoded)
    }

    @Test fun unhealthyOrInvalidDataIsRetainedAsPerSignalUnusableEvidence() {
        assertFalse(decode(altered(0, 153, 2, 1)).decoded!!.e5aStatus.usable)
        assertFalse(decode(altered(0, 187, 1, 1)).decoded!!.e5aStatus.usable)
        assertEquals(decode().decoded!!.ephemeris, decode(altered(0, 153, 2, 2)).decoded!!.ephemeris)
    }
}
