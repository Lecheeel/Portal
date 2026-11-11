package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class RinexNavigationResourceTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val at = Instant.parse("2026-11-07T23:59:42Z").toEpochMilli()
    private fun resource() = RinexNavigationResource.import(navigation(), "https://example.org/navigation.rnx", at + 60000)

    @Test fun validatedResourceAndBoundedWireRetainOriginalEpochsAndText() {
        val resource = resource()
        assertEquals(resource, RinexNavigationResource.decode(RinexNavigationResource.encode(resource)))
        assertEquals(resource, RinexNavigationResource.decodeWire(RinexNavigationResource.encodeWire(resource)))
        val request = GnssSkyRequest(at, Wgs84(31.0, 121.0), 0.0)
        val first = resource.gpsProvider(utc).positions(request).positions.single()
        assertEquals(at, first.provenance.referenceEpochMillis)
        assertNotEquals(resource.downloadedAtMillis, first.provenance.referenceEpochMillis)
        val redownloaded = resource.copy(downloadedAtMillis = at + 86400000)
        assertEquals(first, redownloaded.gpsProvider(utc).positions(request).positions.single())
        assertTrue(redownloaded.gpsProvider(utc).positions(request.copy(atMillis = at + 86400000)).positions.isEmpty())
        assertFalse(RinexNavigationResource.encode(resource).contains("parsed"))
    }

    @Test fun digestVersionSyntaxUrlsAndCompressedExpansionRemainStrict() {
        val resource = resource()
        assertThrows(IllegalArgumentException::class.java) { resource.copy(sha256 = "0".repeat(64)).validate() }
        assertThrows(IllegalArgumentException::class.java) { resource.copy(formatVersion = 2) }
        for (url in listOf("http://example.org/nav", "https:/nav", "https://user:secret@example.org/nav", "https://example.org/nav#frag")) {
            assertThrows(IllegalArgumentException::class.java) { resource.copy(sourceUrl = url) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RinexNavigationResource.import(navigation().replace("G01", "X01"), resource.sourceUrl, at)
        }
        val huge = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(RinexNavigationResource.MAX_JSON_BYTES + 1) { 32 }) }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            RinexNavigationResource.decodeWire(Base64.getEncoder().encodeToString(huge))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RinexNavigationResource.decodeWire("x".repeat(RinexNavigationResource.MAX_WIRE_LENGTH + 1))
        }
    }

    companion object {
        // Circular orbit integration fixture; native GPS calendar midnight (not UTC midnight).
        fun navigation() = "     3.04           NAVIGATION DATA     M".padEnd(60) + "RINEX VERSION / TYPE\n" +
            "".padEnd(60) + "END OF HEADER\n" + """
G01 2026 11 08 00 00 00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00
     7.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00
     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 5.153639490690D+03
     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00
     9.500000000000D-01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00
     0.000000000000D+00 0.000000000000D+00 2.444000000000D+03 0.000000000000D+00
     2.400000000000D+00 0.000000000000D+00 0.000000000000D+00 7.000000000000D+00
    -3.000000000000D+01 4.000000000000D+00
""".trimIndent()
    }
}
