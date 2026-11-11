package com.system.location.service.gnss

import com.system.location.service.core.gnss.RinexNavigationResourceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GnssNavigationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedDownloadsAndValidationDoNotReplaceCacheAndIdenticalUploadsDoNotRestartSky() = runBlocking {
        val file = temporary.root.resolve("navigation.json")
        var fail = false
        val status = GnssNavigationSyncStatus()
        val repository = GnssNavigationRepository(file, GnssNavigationDownload {
            if (fail) error("network down") else archive()
        }, status)
        assertTrue(repository.refresh(1794369600000))
        assertEquals(1794369600000L, status.state.value.acceptedAtMillis)
        val old = file.readBytes()
        fail = true
        try { repository.refresh(1794369601000); fail("refresh should fail") } catch (_: IllegalStateException) { }
        assertArrayEquals(old, file.readBytes())
        assertFalse(status.state.value.refreshing)
        assertNotNull(status.state.value.problem)
        assertEquals(1794369600000L, status.state.value.acceptedAtMillis)
        fail = false
        assertFalse(repository.refresh(1794369602000))
        assertNull(status.state.value.problem)
        assertEquals(1794369602000L, status.state.value.acceptedAtMillis)
        val resource = RinexNavigationResourceStore(file).load()!!
        assertEquals(1794369602000, resource.downloadedAtMillis)
        val broken = GnssNavigationRepository(file, GnssNavigationDownload { "corrupt" })
        try { broken.refresh(1794369603000); fail("invalid archive should fail") } catch (_: IllegalArgumentException) { }
        assertEquals(resource, RinexNavigationResourceStore(file).load())
    }

    private fun archive() = "     3.04           NAVIGATION DATA     M".padEnd(60) + "RINEX VERSION / TYPE\n" +
        "".padEnd(60) + "END OF HEADER\n" + """
R01 2026 11 11 00 15 00 1.000000000000D-04 2.000000000000D-12 4.320000000000D+05
     1.910000000000D+04 1.100000000000D+00 1.000000000000D-09 0.000000000000D+00
     1.340000000000D+04-2.200000000000D+00-2.000000000000D-09-7.000000000000D+00
     1.090000000000D+04 3.000000000000D-01 3.000000000000D-09 1.000000000000D+00
""".trimIndent()
}
