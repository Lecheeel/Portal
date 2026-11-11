package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BeiDouSkyCatalogTest {
    private val metadata = BeiDouSatelliteMetadata.parse(requireNotNull(javaClass.getResource("/gnss/igs-beidou-metadata.snx")).readText())
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun epochAmbiguityCannotChooseAnIdentityAcrossTheActual2026Switch() {
        val catalog = BeiDouSkyCatalog(metadata, 2026, 30 * 86400L, utc)
        assertEquals("C005", catalog.identityAt(6, at("2026-04-10T11:58:00Z"))?.svn)
        assertNull(catalog.identityAt(6, at("2026-04-10T11:59:59Z")))
        assertNull(catalog.identityAt(6, at("2026-04-10T16:00:00Z")))
        assertEquals("C220", catalog.identityAt(6, at("2026-04-10T16:00:02Z"))?.svn)
        assertEquals(BeiDouOrbitType.GEO, catalog.identityAt(4, at("2026-11-11T03:00:00Z"))?.orbitType)
    }

    @Test fun sourceAgeAndTimeEvidenceAreRequiredEvenForOpenEndedAssignments() {
        val catalog = BeiDouSkyCatalog(metadata, 2026, 30 * 86400L, utc)
        assertNull(catalog.identityAt(4, at("2026-11-22T00:00:00Z")))
        assertNull(catalog.identityAt(4, utc.expiresAtMillis))
        assertNull(catalog.identityAt(64, at("2026-11-11T03:00:00Z")))
        assertThrows(IllegalArgumentException::class.java) { BeiDouSkyCatalog(metadata, 2025, 86400, utc) }
    }
}
