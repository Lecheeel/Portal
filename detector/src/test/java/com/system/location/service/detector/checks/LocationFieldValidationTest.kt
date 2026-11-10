package com.system.location.service.detector.checks

import org.junit.Assert.*
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
}
