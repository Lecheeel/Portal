package com.system.location.service.gnss

import com.system.location.service.core.gnss.RinexNavigationResource
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GnssNavigationDownloadTest {
    @Test fun utcDayAndYearRolloverDoNotFollowTheHandsetsLocalZone() {
        val at = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli()
        val urls = GnssNavigationUrls.candidates(at)
        assertTrue(urls[0].endsWith("2027/001/BRDC00WRD_R_20270010000_01D_MN.rnx.gz"))
        assertTrue(urls[1].endsWith("2026/365/BRDC00WRD_R_20263650000_01D_MN.rnx.gz"))
        assertTrue(GnssNavigationUrls.candidates(Instant.parse("2024-12-31T23:59:59Z").toEpochMilli())[0]
            .contains("2024/366/"))
    }

    @Test fun unpublishedDayFallsBackButCorruptPublishedArchiveCannotMasqueradeAsMissing() {
        val calls = mutableListOf<String>()
        val now = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
        val result = GnssNavigationDownload { url ->
            calls += url
            if (calls.size == 1) null else archive()
        }.fetch(now)
        assertEquals(2, calls.size)
        assertEquals(calls[1], result.sourceUrl)
        assertEquals(now, result.downloadedAtMillis)
        assertEquals(result, RinexNavigationResource.decodeWire(RinexNavigationResource.encodeWire(result)))
        calls.clear()
        assertThrows(IllegalArgumentException::class.java) {
            GnssNavigationDownload { url -> calls += url; "corrupt" }.fetch(now)
        }
        assertEquals(1, calls.size)
        assertThrows(IllegalStateException::class.java) { GnssNavigationDownload { null }.fetch(now) }
    }

    private fun archive() = "     3.05           NAVIGATION DATA     M".padEnd(60) + "RINEX VERSION / TYPE\n" +
        "".padEnd(60) + "END OF HEADER\n" + """
R01 2026 11 11 00 15 00 1.000000000000D-04 2.000000000000D-12 4.320000000000D+05
     1.910000000000D+04 1.100000000000D+00 1.000000000000D-09 0.000000000000D+00
     1.340000000000D+04-2.200000000000D+00-2.000000000000D-09-7.000000000000D+00
     1.090000000000D+04 3.000000000000D-01 3.000000000000D-09 1.000000000000D+00
     1.920000000000D+02 0.000000000000D+00 1.500000000000D+01 5.000000000000D+00
""".trimIndent()
}
