package com.system.location.service.detector.checks

import org.junit.Assert.*
import com.system.location.service.detector.model.*
import org.junit.Test

class LocationFieldValidationTest {
    private fun issues(fields: Map<String, Double>) = LocationFieldValidation.issues(31.0, 121.0, fields)

    @Test fun optionalGnssAndAccuracyFieldsMayBeAbsentForAnyProvider() {
        assertTrue(issues(emptyMap()).isEmpty())
        assertTrue(issues(mapOf("speed" to 0.0, "bearing" to 45.0)).isEmpty())
        assertTrue(issues(mapOf("satellites" to 0.0, "altitude" to -45.0)).isEmpty())
    }

    @Test fun zeroUncertaintyAndAccuracyAreValid() {
        val fields = listOf("accuracy", "verticalAccuracy", "speedAccuracy", "bearingAccuracy",
            "elapsedUncertainty", "mslAccuracy", "meanCn0", "maxCn0")
        assertTrue(issues(fields.associateWith { 0.0 }).isEmpty())
    }

    @Test fun suppliedNonFiniteAndNegativeUncertaintyAreErrors() {
        for (field in listOf("altitude", "mslAltitude", "speed", "accuracy", "elapsedUncertainty", "meanCn0")) {
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertFalse("$field=$invalid", issues(mapOf(field to invalid)).isEmpty())
            }
        }
        assertFalse(issues(mapOf("speedAccuracy" to -1.0)).isEmpty())
    }

    @Test fun coordinatesBearingAndGnssExtrasRespectTheirOwnDomains() {
        assertFalse(LocationFieldValidation.issues(91.0, 121.0, emptyMap()).isEmpty())
        assertFalse(LocationFieldValidation.issues(31.0, -181.0, emptyMap()).isEmpty())
        assertFalse(issues(mapOf("bearing" to 360.0)).isEmpty())
        assertFalse(issues(mapOf("satellites" to 3.5)).isEmpty())
        assertFalse(issues(mapOf("meanCn0" to 64.0)).isEmpty())
        assertFalse(issues(mapOf("meanCn0" to 40.0, "maxCn0" to 30.0)).isEmpty())
    }
    private fun frame(at: Long = 10000): GnssFrameSnapshot {
        val satellite = GnssSatelliteSnapshot(1, 1, 30f, 40f, 50f, true, true, true, true)
        return GnssFrameSnapshot(at, listOf(satellite, satellite.copy(cn0DbHz = 40f),
            satellite.copy(svid = 2, usedInFix = false)))
    }

    @Test fun gpsExtrasCompareUniqueUsedSatellitesRatherThanVisibleOrSignalCount() {
        assertTrue(LocationGnssReconciliation.compare("gps", 10000,
            mapOf("satellites" to 1.0, "meanCn0" to 35.0), listOf(frame())).isEmpty())
        val mismatches = LocationGnssReconciliation.compare("gps", 10000,
            mapOf("satellites" to 12.0, "maxCn0" to 45.0), listOf(frame()))
        assertEquals(2, mismatches.size)
    }

    @Test fun networkOrStaleEvidenceDoesNotParticipateInGnssReconciliation() {
        for ((provider, at) in listOf("network" to 10000L, "gps" to 5000L)) {
            assertTrue(LocationGnssReconciliation.compare(provider, at,
                mapOf("satellites" to 12.0), listOf(frame())).isEmpty())
        }
    }

    @Test fun laterFusedCallbackCannotHideEarlierGpsContradiction() {
        val samples = listOf(
            LocationFieldSample("gps", 10000, 31.0, 121.0, mapOf("satellites" to 12.0)),
            LocationFieldSample("fused", 10001, 31.0, 121.0, emptyMap()),
        )
        val result = LocationWindowValidation.evaluate(samples, listOf(frame()))
        assertTrue(result.issues.isEmpty())
        assertEquals(1, result.discrepancies.size)
        assertEquals(1, result.gpsExtrasCount)
        assertEquals(1, result.comparedGpsCount)
    }

    @Test fun windowRetainsInvalidEarlierFieldsAndSeparatesMissingComparisonCoverage() {
        val samples = listOf(
            LocationFieldSample("network", 10000, 91.0, 121.0, mapOf("accuracy" to -1.0)),
            LocationFieldSample("gps", 5000, 31.0, 121.0, mapOf("satellites" to 12.0)),
            LocationFieldSample("gps", 10000, 31.0, 121.0, emptyMap()),
            LocationFieldSample("fused", 10001, 31.0, 121.0, emptyMap()),
        )
        val result = LocationWindowValidation.evaluate(samples, listOf(frame()))
        assertEquals(2, result.issues.size)
        assertTrue(result.discrepancies.isEmpty())
        assertEquals(1, result.gpsExtrasCount)
        assertEquals(0, result.comparedGpsCount)
    }

    @Test fun coherentMovingHighRateGpsSamplesAreAllReconciled() {
        val samples = (0..9).map { index -> LocationFieldSample("gps", 10000 + index * 100L,
            31.0 + index * 0.00001, 121.0, mapOf("satellites" to 1.0, "meanCn0" to 35.0)) }
        val result = LocationWindowValidation.evaluate(samples, listOf(frame()))
        assertTrue(result.issues.isEmpty())
        assertTrue(result.discrepancies.isEmpty())
        assertEquals(10, result.gpsExtrasCount)
        assertEquals(10, result.comparedGpsCount)
    }

}
