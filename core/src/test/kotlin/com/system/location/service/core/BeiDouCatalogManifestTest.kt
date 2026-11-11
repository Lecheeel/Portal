package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BeiDouCatalogManifestTest {
    private val manifest = BeiDouCatalogManifest.decode(requireNotNull(javaClass.getResource("/gnss/igs-beidou-metadata-source.json")).readText())
    private val subset = requireNotNull(javaClass.getResource("/gnss/igs-beidou-metadata.snx")).readText()
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())

    @Test fun manifestVerifiesCanonicalSubsetAndSuppliesSourceEraWithoutHardcodedRuntimeYear() {
        val at = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        val catalog = manifest.catalog(subset, utc, 30 * 86400L)
        assertEquals("C217", catalog.identityAt(4, at)?.svn)
        assertTrue(catalog.description.contains(manifest.sourceUrl))
        assertEquals(catalog.identityAt(4, at), manifest.catalog(subset.replace("\n", "\r\n"), utc, 30 * 86400L).identityAt(4, at))
        assertThrows(IllegalArgumentException::class.java) { manifest.catalog(subset.replace("BDS-3G", "BDS-3I"), utc, 86400) }
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(fullCreationYear = 2025).catalog(subset, utc, 86400) }
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(sourceUrl = "http://example.org/meta") }
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(formatVersion = 2) }
    }
}
