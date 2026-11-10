package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.location.LocationSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSanitizerTest {

    private fun sample(
        speed: Float = 0f,
        accuracy: Float = 8f,
        altitude: Double = 80.0,
    ) = LocationSample(
        coordinate = Wgs84(31.2304, 121.4737),
        altitude = altitude,
        accuracy = accuracy,
        speed = speed,
        bearing = 0f,
        timeMillis = 1_790_000_000_000,
        elapsedNanos = 1_000_000_000,
    )

    @Test
    fun verticalAccuracyIsWorseThanHorizontal() {
        val fields = LocationSanitizer.fieldsFor(sample(accuracy = 8f))
        assertTrue(fields.verticalAccuracyMeters > 8f)
    }

    @Test
    fun stationaryFixHasNoBearingOrSpeedAccuracy() {
        val fields = LocationSanitizer.fieldsFor(sample(speed = 0f))
        assertEquals(0f, fields.speedAccuracyMetersPerSecond, 0f)
        assertEquals(0f, fields.bearingAccuracyDegrees, 0f)
    }

    @Test
    fun movingFixHasSpeedAndBearingAccuracy() {
        val fields = LocationSanitizer.fieldsFor(sample(speed = 12f))
        assertTrue(fields.speedAccuracyMetersPerSecond > 0f)
        assertEquals(2f, fields.bearingAccuracyDegrees, 0f)
    }

    @Test
    fun missingFieldsUseFiniteDefaults() {
        val fields = LocationSanitizer.fieldsFor(sample())
        assertTrue(fields.verticalAccuracyMeters.isFinite())
        assertTrue(fields.elapsedRealtimeUncertaintyNanos in 0..LocationSanitizer.MAX_UNCERTAINTY_NANOS)
    }

    @Test
    fun invalidAccuracyFallsBackToFinitePositiveValue() {
        assertEquals(25f, LocationSanitizer.sanitizeAccuracy(Float.NaN, 25f), 0f)
        assertEquals(25f, LocationSanitizer.sanitizeAccuracy(-1f, 25f), 0f)
        assertEquals(5f, LocationSanitizer.sanitizeAccuracy(5f, 25f), 0f)
    }

    @Test
    fun outOfRangeAltitudeFallsBack() {
        assertEquals(0.0, LocationSanitizer.sanitizeAltitude(Double.NaN), 0.0)
        assertEquals(0.0, LocationSanitizer.sanitizeAltitude(1e9), 0.0)
        assertEquals(80.0, LocationSanitizer.sanitizeAltitude(80.0), 0.0)
    }

    @Test
    fun extrasDropInvalidValues() {
        val extras = LocationSanitizer.sanitizeExtras(
            mapOf("satellites" to 12, "meanCn0" to Float.NaN, "maxCn0" to 44f, "unexpected" to "x"),
        )
        assertEquals(setOf("satellites", "maxCn0"), extras.keys)
        assertEquals(12, extras["satellites"])
        assertEquals(44f, extras["maxCn0"])
    }
}
