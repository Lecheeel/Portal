package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BeiDouCatalogResourceTest {
    private val text = requireNotNull(javaClass.getResource("/gnss/igs-beidou-metadata.snx")).readText()
    private val modified = Instant.parse("2026-10-22T08:30:06Z").toEpochMilli()

    @Test fun compactResourcePreservesAssignmentsAndWireRoundTrips() {
        val resource = BeiDouCatalogResource.import(text, modified)
        assertEquals(2026, resource.manifest.fullCreationYear)
        val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
        assertEquals("C217", resource.catalog(utc, 30 * 86400L)
            .identityAt(4, Instant.parse("2026-11-11T03:00:00Z").toEpochMilli())?.svn)
        val navigation = RinexNavigationResource.import(RinexNavigationResourceTest.navigation(),
            "https://example.org/navigation.rnx", modified).copy(beiDouCatalog = resource)
        assertEquals(navigation, RinexNavigationResource.decodeWire(RinexNavigationResource.encodeWire(navigation)))
        assertThrows(IllegalArgumentException::class.java) {
            navigation.copy(beiDouCatalog = resource.copy(subset = resource.subset.replace("BDS-3G", "BDS-3I"))).validate()
        }
    }

    @Test fun sourceYearEvidenceDoesNotRedateAnOldCatalogAndHandlesYearBoundary() {
        assertThrows(IllegalStateException::class.java) { BeiDouCatalogResource.import(text,
            Instant.parse("2126-10-22T08:30:06Z").toEpochMilli() - 366 * 86400000L) }
        val december = text.replace("26:295:28054", "26:365:28054")
        val resource = BeiDouCatalogResource.import(december, Instant.parse("2027-01-01T00:00:00Z").toEpochMilli())
        assertEquals(2026, resource.creationLabel.year)
        assertEquals(365, resource.creationLabel.dayOfYear)
        assertThrows(IllegalArgumentException::class.java) { BeiDouCatalogResource.import(text, 0) }
    }
}
