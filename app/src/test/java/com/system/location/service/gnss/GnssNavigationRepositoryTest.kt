package com.system.location.service.gnss

import com.system.location.service.core.gnss.RinexNavigationResourceStore
import com.system.location.service.core.gnss.BeiDouCatalogResource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GnssNavigationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun metadataUpdatesIndependentlyAndAnOutageCannotEraseAcceptedCatalog() = runBlocking {
        val file = temporary.root.resolve("metadata-navigation.json")
        val now = 1794369600000L
        val text = requireNotNull(javaClass.getResource("/gnss/igs-beidou-metadata.snx")).readText()
        var metadata = BeiDouCatalogResource.import(text, now)
        var metadataFails = false
        var navigationFails = false
        val status = GnssNavigationSyncStatus()
        val repository = GnssNavigationRepository(file,
            GnssNavigationDownload { if (navigationFails) error("archive down") else archive() }, status,
            GnssBeiDouCatalogDownload { if (metadataFails) error("metadata down") else metadata })
        assertTrue(repository.refresh(now))
        assertEquals(metadata, RinexNavigationResourceStore(file).load()!!.beiDouCatalog)
        assertFalse(repository.refresh(now + 1000))
        metadata = BeiDouCatalogResource.import(text.replace("26:295:28054", "26:296:28054"), now)
        assertTrue(repository.refresh(now + 2000)) // Same RINEX bytes; metadata alone changes sky.
        metadataFails = true
        assertFalse(repository.refresh(now + 3000))
        assertNotNull(status.state.value.problem)
        assertEquals(metadata, RinexNavigationResourceStore(file).load()!!.beiDouCatalog)
        metadataFails = false
        navigationFails = true
        metadata = BeiDouCatalogResource.import(text.replace("26:295:28054", "26:297:28054"), now)
        assertTrue(repository.refresh(now + 4000)) // Metadata server is independent of BKG.
        assertEquals(metadata, RinexNavigationResourceStore(file).load()!!.beiDouCatalog)
        assertTrue(status.state.value.problem!!.contains("广播导航下载失败"))
        val bytes = file.readBytes()
        metadataFails = true
        try { repository.refresh(now + 5000); fail("both downloads failed") } catch (_: IllegalStateException) { }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun failedDownloadsAndValidationDoNotReplaceCacheAndIdenticalUploadsDoNotRestartSky() = runBlocking {
        val file = temporary.root.resolve("navigation.json")
        var fail = false
        val status = GnssNavigationSyncStatus()
        val repository = GnssNavigationRepository(file, GnssNavigationDownload {
            if (fail) error("network down") else archive()
        }, status, catalogDownload = null)
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
        val broken = GnssNavigationRepository(file, GnssNavigationDownload { "corrupt" }, catalogDownload = null)
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
