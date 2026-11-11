package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RinexGalileoImporterTest {
    private val record = RinexNavigationRecord(RinexNavigationSystem.GALILEO, 1,
        RinexNavigationEpoch(LocalDate.of(2020, 5, 15), 0, 0, 0), listOf(-7.3e-4, -1.1e-11, 0.0),
        listOf(listOf(66.0, -20.9375, 2.8e-9, -2.6), listOf(-1.1e-6, 0.000135, 9.9e-6, 5440.6),
            listOf(432000.0, -2.6e-8, 2.45, -1.9e-8), listOf(0.971, 129.0, 1.18, -5.6e-9),
            listOf(2.4e-10, 517.0, 2105.0, null), listOf(3.12, 0.0, 4.9e-9, 5.1e-9),
            listOf(432650.0, null, null, null)))
    private fun change(row: Int, column: Int, value: Double?) = record.copy(orbit = record.orbit.mapIndexed { r, values ->
        if (r == row) values.mapIndexed { c, old -> if (c == column) value else old } else values
    })

    @Test fun rinexGpsAlignedWeekBecomesNativeGstWithoutAssumingInterSystemOffset() {
        val imported = RinexGalileoImporter.decode(record)
        val e = imported.ephemeris
        assertEquals(GalileoTime(1081, 432000.0), e.toe)
        assertEquals(e.toe, e.toc)
        assertEquals(GalileoTime(1081, 432650.0), imported.availableFrom)
        assertEquals(GalileoClockReference.INAV_E1_E5B, e.clockReference)
        assertEquals(e.toe, e.validFrom)
        assertEquals(14400.0, e.validUntil.secondsSince(e.toe), 0.0)
        assertEquals(-7.3e-4, e.af0Seconds, 0.0)
        assertEquals(-1.1e-11, e.af1SecondsPerSecond, 0.0)
        assertEquals(-20.9375, e.crsMeters, 0.0)
        assertEquals(129.0, e.crcMeters, 0.0)
        assertNotNull(GalileoBroadcastPropagator.propagate(e, e.toe).state)
        assertNull(GalileoBroadcastPropagator.propagate(e, e.toe.plusSeconds(-1.0)).state)
        assertNull(GalileoBroadcastPropagator.propagate(e, e.toe.plusSeconds(14401.0)).state)
    }

    @Test fun unavailableSignalFieldsAreNotMistakenForHealthyZeroBits() {
        val inav = RinexGalileoImporter.decode(change(5, 1, 1.0)).service
        assertEquals(false, inav.e1bHealthy) // E1-B data validity status set.
        assertEquals(true, inav.e5bHealthy)
        assertNull(inav.e5aHealthy)
        val fnavRecord = change(4, 1, 258.0)
        val fnav = RinexGalileoImporter.decode(fnavRecord)
        assertEquals(GalileoClockReference.FNAV_E1_E5A, fnav.ephemeris.clockReference)
        assertNull(fnav.service.e1bHealthy)
        assertNull(fnav.service.e5bHealthy)
        assertEquals(true, fnav.service.e5aHealthy)
        assertNull(fnav.bgdE5bE1Seconds)
        val unhealthyFnav = fnavRecord.copy(orbit = fnavRecord.orbit.mapIndexed { row, values ->
            if (row == 5) listOf(3.12, 8.0, 4.9e-9, null) else values
        })
        assertEquals(false, RinexGalileoImporter.decode(unhealthyFnav).service.e5aHealthy)
        assertFalse(RinexGalileoImporter.decode(change(5, 0, -1.0)).service.accuracyAvailable)
    }

    @Test fun inconsistentSourcesMissingValuesAndWrongWeekAreRejected() {
        val bad = listOf(0.0, 7.0, 256.0, 512.0, 519.0, 514.0, 769.0, 549.0, 517.5)
            .map { change(4, 1, it) } + listOf(change(4, 2, 1081.0), change(5, 1, null),
                change(6, 0, null), change(6, 0, 999999999.999), change(5, 0, -2.0))
        for (input in bad) assertThrows(IllegalArgumentException::class.java) { RinexGalileoImporter.decode(input) }
        for (source in listOf(513.0, 516.0, 517.0, 258.0)) {
            assertNotNull(RinexGalileoImporter.decode(change(4, 1, source)))
        }
    }
}
