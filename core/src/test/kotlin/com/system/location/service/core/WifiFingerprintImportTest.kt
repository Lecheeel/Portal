package com.system.location.service.core

import com.system.location.service.core.environment.WifiFingerprint
import com.system.location.service.core.environment.WifiFingerprintCatalog
import com.system.location.service.core.environment.WifiFingerprintImportException
import com.system.location.service.core.environment.WifiFingerprintImporter
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiFingerprintImportTest {
    private val fingerprint = WifiFingerprint("AA:BB:CC:DD:EE:01", Wgs84(31.23, 121.47), -55, 1_000)

    @Test fun catalogDefaultsToDisabled() {
        assertFalse(WifiFingerprintCatalog().enabled)
        assertEquals(1, WifiFingerprintCatalog.SCHEMA_VERSION)
    }

    @Test fun enabledCatalogRequiresLicenseAndAttribution() {
        assertThrows(IllegalArgumentException::class.java) {
            WifiFingerprintCatalog(enabled = true, source = "wigle", license = "", attribution = "WigLE")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WifiFingerprintCatalog(enabled = true, source = "wigle", license = "CC-BY", attribution = "")
        }
        assertTrue(
            WifiFingerprintCatalog(enabled = true, source = "wigle", license = "CC-BY", attribution = "WigLE").enabled,
        )
    }

    @Test fun jsonRoundTripsWithAttribution() {
        val catalog = WifiFingerprintCatalog(
            source = "wigle",
            license = "CC-BY-4.0",
            attribution = "WigLE",
            entries = listOf(fingerprint),
        )
        val decoded = WifiFingerprintImporter.importJson(WifiFingerprintImporter.encodeJson(catalog)).catalog
        assertEquals(catalog, decoded)
        assertEquals(1, decoded.database().entries.size)
    }

    @Test fun csvImportsAndSkipsBadRows() {
        val csv = """
            bssid,latitude,longitude,rssi,observedAtMillis
            AA:BB:CC:DD:EE:01,31.23,121.47,-55,1000
            bad-row
            AA:BB:CC:DD:EE:02,31.24,121.48,-60,1000
        """.trimIndent()
        val result = WifiFingerprintImporter.importCsv(csv, "self", "CC0", "self-captured")
        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        assertFalse(result.catalog.enabled)
        assertEquals(2, result.catalog.database().entries.size)
    }

    @Test fun csvRequiresMetadataAndValidRows() {
        assertThrows(IllegalArgumentException::class.java) {
            WifiFingerprintImporter.importCsv("AA:BB:CC:DD:EE:01,31.23,121.47,-55,1000", "", "CC0", "self")
        }
        assertThrows(WifiFingerprintImportException::class.java) {
            WifiFingerprintImporter.importCsv("bad\nworse", "self", "CC0", "self")
        }
    }

    @Test fun invalidJsonIsRejected() {
        assertThrows(WifiFingerprintImportException::class.java) {
            WifiFingerprintImporter.importJson("not-json")
        }
        assertThrows(WifiFingerprintImportException::class.java) {
            WifiFingerprintImporter.importJson("""{"schemaVersion":99}""")
        }
    }
}
