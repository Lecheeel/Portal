package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class RinexNavigationSnapshotTest {
    private val text = RinexNavigationResourceTest.navigation()
    private val header = text.substringBefore("G01")
    private val body = text.substringAfter("END OF HEADER\n")
    private fun upload(transmission: Double, health: Int = 0, svid: String = "01"): String = body
        .replace("G01", "G$svid")
        .replace("2.400000000000D+00 0.000000000000D+00", "2.400000000000D+00 ${health}.000000000000D+00")
        .replace("-3.000000000000D+01", "%19.12E".format(java.util.Locale.ROOT, transmission))

    @Test fun latestTransmissionWinsOverCalendarAndHealthIsNeverFilteredOut() {
        val archive = header + listOf(upload(10.0), upload(30.0, 1), upload(20.0), upload(5.0, svid = "02"))
            .joinToString("\n")
        val selected = RinexNavigationSnapshot.select(archive, "https://example.org/nav", 1, uploadsPerIdentity = 2)
        val records = RinexNavigationReader.read(selected.navigation).records
        assertEquals(3, records.size)
        assertEquals(listOf(30.0, 20.0), records.filter { it.svid == 1 }.map { it.orbit[6][0] })
        assertEquals(1.0, records.first().orbit[5][1])
        assertTrue(selected.navigation.startsWith(header))
        assertEquals(1L, selected.downloadedAtMillis)
        assertEquals(selected, RinexNavigationResource.decodeWire(RinexNavigationResource.encodeWire(selected)))
    }

    @Test fun sameTransmissionConflictsAndUnknownTransmissionRemainAvailableForValidation() {
        val archive = header + listOf(upload(10.0), upload(30.0, 1), upload(30.0), upload(999999999.999))
            .joinToString("\n")
        val selected = RinexNavigationSnapshot.select(archive, "https://example.org/nav", 1, uploadsPerIdentity = 1)
        val records = RinexNavigationReader.read(selected.navigation).records
        assertEquals(3, records.size)
        assertEquals(listOf(1.0, 0.0, 0.0), records.map { it.orbit[5][1] })
        assertEquals(listOf(30.0, 30.0, 999999999.999), records.map { it.orbit[6][0] })
        assertThrows(IllegalArgumentException::class.java) {
            RinexNavigationSnapshot.select(archive, "https://example.org/nav", 1, uploadsPerIdentity = 0)
        }
    }
}
