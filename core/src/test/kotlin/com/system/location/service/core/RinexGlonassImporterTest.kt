package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RinexGlonassImporterTest {
    private val record = RinexNavigationRecord(RinexNavigationSystem.GLONASS, 3,
        RinexNavigationEpoch(LocalDate.of(2026, 11, 8), 0, 15, 0), listOf(-1e-4, 2e-12, 900.0),
        listOf(listOf(19100.0, 1.1, 1e-9, 0.0), listOf(13400.0, -2.2, -2e-9, -7.0),
            listOf(10900.0, 0.3, 3e-9, 1.0)))
    private fun decode(record: RinexNavigationRecord) = RinexGlonassImporter.decode(record, GlonassReferenceFrame.PZ_90_11)

    @Test fun kilometersBecomeSiButUtcEpochAndClockSignArePreserved() {
        val result = decode(record)
        assertEquals(GlonassCartesianVector(19100000.0, 13400000.0, 10900000.0), result.orbit.positionMeters)
        assertEquals(GlonassCartesianVector(1100.0, -2200.0, 300.0), result.orbit.velocityMetersPerSecond)
        val acc = result.orbit.luniSolarAccelerationMetersPerSecondSquared
        assertEquals(1e-6, acc.x, 1e-20)
        assertEquals(-2e-6, acc.y, 1e-20)
        assertEquals(3e-6, acc.z, 1e-20)
        assertEquals(record.epoch, result.referenceUtc)
        assertEquals(-1e-4, result.clockBiasSeconds, 0.0)
        assertEquals(2e-12, result.relativeFrequencyBias, 0.0)
        assertEquals(900.0, result.frameTimeUtcWeekSeconds, 0.0)
        assertEquals(-7, result.frequencyNumber)
        assertFalse(result.unhealthy)
        assertEquals(1, result.ageDays)
        assertNull(result.statusFlags)
        assertNull(result.healthFlags)
        assertNull(result.accuracyIndex)
        assertEquals(GlonassReferenceFrame.PZ_90_11, result.orbit.referenceFrame)
        assertEquals(GlonassReferenceFrame.PZ_90_02,
            RinexGlonassImporter.decode(record, GlonassReferenceFrame.PZ_90_02).orbit.referenceFrame)
    }

    @Test fun optional305FieldsPreserveUnknownAndRawSignalEvidence() {
        val unknown = decode(record.copy(orbit = record.orbit + listOf(listOf(null, 999999999.999, 15.0, null))))
        assertNull(unknown.statusFlags)
        assertNull(unknown.l1L2GroupDelaySeconds)
        assertNull(unknown.accuracyIndex)
        assertNull(unknown.healthFlags)
        val known = decode(record.copy(orbit = record.orbit + listOf(listOf(192.0, -2e-9, 3.0, 5.0))))
        assertEquals(192, known.statusFlags)
        assertEquals(-2e-9, known.l1L2GroupDelaySeconds!!, 0.0)
        assertEquals(3, known.accuracyIndex)
        assertEquals(5, known.healthFlags)
    }

    @Test fun invalidUnitsSlotsAndRequiredFieldsDoNotBecomePropagatableStates() {
        val bad = listOf(record.copy(svid = 0), record.copy(clock = listOf(0.0, 0.0, -1.0)),
            record.copy(orbit = listOf(listOf(19100.0, 1.1, 1e-9, 2.0)) + record.orbit.drop(1)),
            record.copy(orbit = listOf(listOf(19100000.0, 1.1, 1e-9, 0.0)) + record.orbit.drop(1)),
            record.copy(orbit = record.orbit + listOf(listOf(512.0, null, null, null))),
            record.copy(orbit = record.orbit + listOf(listOf(null, null, null, 0.5))))
        for (input in bad) assertThrows(IllegalArgumentException::class.java) { decode(input) }
    }
}
