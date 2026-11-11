package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDate
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RinexBeiDouImporterTest {
    private val toe = BeiDouTime(1088, 0.0)
    private val from = toe.plusSeconds(-3600.0)
    private val until = toe.plusSeconds(3600.0)
    private val record = RinexNavigationRecord(RinexNavigationSystem.BEIDOU, 6,
        RinexNavigationEpoch(LocalDate.of(2026, 11, 8), 0, 0, 0), listOf(1e-4, 2e-12, 3e-20),
        listOf(listOf(4.0, 10.0, 2e-9, 0.5), listOf(1e-6, 0.01, 2e-6, sqrt(42164000.0)),
            listOf(0.0, 3e-8, 0.8, 4e-8), listOf(0.1, 20.0, 1.0, -6e-9),
            listOf(7e-10, null, 1088.0, null), listOf(2.4, 0.0, 5e-9, -6e-9),
            listOf(-30.0, 5.0, null, null)))
    private fun change(row: Int, column: Int, value: Double?) = record.copy(orbit = record.orbit.mapIndexed { r, values ->
        if (r == row) values.mapIndexed { c, old -> if (c == column) value else old } else values
    })

    @Test fun bdtWeekIsNotGpsAndAgeFieldsAreNotFitOrIssueIdentifiers() {
        val imported = RinexBeiDouImporter.decode(record, BeiDouOrbitType.IGSO, from, until)
        assertEquals(toe, imported.ephemeris.toe)
        assertEquals(toe, imported.ephemeris.toc)
        assertEquals(toe.plusSeconds(-30.0), imported.availableFrom)
        assertEquals(from, imported.ephemeris.validFrom)
        assertEquals(until, imported.ephemeris.validUntil)
        assertEquals(4, imported.ageOfDataEphemeris)
        assertEquals(5, imported.ageOfDataClock)
        assertEquals(2.4, imported.accuracyMeters, 0.0)
        assertEquals(5e-9, imported.tgd1Seconds, 0.0)
        assertEquals(-6e-9, imported.tgd2Seconds, 0.0)
        assertEquals(1e-4, imported.ephemeris.af0Seconds, 0.0)
        assertEquals(10.0, imported.ephemeris.crsMeters, 0.0)
        assertEquals(20.0, imported.ephemeris.crcMeters, 0.0)
    }

    @Test fun geoAndIgsoUseExplicitClassificationInsteadOfPrnHeuristics() {
        val geo = RinexBeiDouImporter.decode(record, BeiDouOrbitType.GEO, from, until).ephemeris
        val igso = RinexBeiDouImporter.decode(record, BeiDouOrbitType.IGSO, from, until).ephemeris
        assertEquals(geo.svid, igso.svid)
        assertNotEquals(BeiDouBroadcastPropagator.propagate(geo, toe.plusSeconds(100.0)).state,
            BeiDouBroadcastPropagator.propagate(igso, toe.plusSeconds(100.0)).state)
        assertNull(BeiDouBroadcastPropagator.propagate(geo, until.plusSeconds(1.0)).state)
    }

    @Test fun missingOrInconsistentExternalApplicabilityAndFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RinexBeiDouImporter.decode(record, BeiDouOrbitType.GEO, toe, until)
        }
        for (bad in listOf(change(4, 2, 2444.0), change(6, 0, 999999999.999), change(6, 0, null),
            change(6, 1, 32.0), change(0, 0, 4.5), change(5, 1, 2.0), change(5, 3, null))) {
            assertThrows(IllegalArgumentException::class.java) {
                RinexBeiDouImporter.decode(bad, BeiDouOrbitType.GEO, from, until)
            }
        }
        assertEquals(1, RinexBeiDouImporter.decode(change(5, 1, 1.0), BeiDouOrbitType.GEO, from, until).health)
    }
}
