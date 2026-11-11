package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderFieldMask
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.providers.ProviderMixer
import com.system.location.service.core.providers.ProviderProfile
import com.system.location.service.core.providers.StationaryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMixerTest {
    private val mixer = ProviderMixer()
    private val moving = LocationSample(
        coordinate = Wgs84(31.2304, 121.4737),
        altitude = 80.0,
        accuracy = 25f,
        speed = 12f,
        bearing = 87f,
        timeMillis = 1_790_000_000_000,
        elapsedNanos = 1_000_000_000,
    )

    @Test
    fun threeProvidersDifferInAccuracyLatencyAndFields() {
        val samples = mixer.mix(moving, satellites = 12, meanCn0 = 32f, maxCn0 = 44f)
        assertEquals(3, samples.size)
        val gps = samples.getValue(ProviderKind.GPS)
        val network = samples.getValue(ProviderKind.NETWORK)
        val fused = samples.getValue(ProviderKind.FUSED)

        assertTrue(gps.profile.accuracyMeters < fused.profile.accuracyMeters)
        assertTrue(fused.profile.accuracyMeters < network.profile.accuracyMeters)
        assertEquals(0L, gps.profile.latencyMs)
        assertEquals(120L, fused.profile.latencyMs)
        assertEquals(400L, network.profile.latencyMs)
        assertTrue(gps.has(ProviderFieldMask.BEARING))
        assertTrue(gps.has(ProviderFieldMask.SPEED))
        assertTrue(gps.has(ProviderFieldMask.ALTITUDE))
        assertFalse(network.has(ProviderFieldMask.BEARING))
        assertFalse(network.has(ProviderFieldMask.SPEED))
        assertFalse(network.has(ProviderFieldMask.ALTITUDE))
        assertNull(network.satellites)
        assertNotNull(gps.satellites)
    }

    @Test
    fun networkTimeIsOlderThanGpsTime() {
        val samples = mixer.mix(moving)
        assertTrue(samples.getValue(ProviderKind.NETWORK).sample.timeMillis <
            samples.getValue(ProviderKind.GPS).sample.timeMillis)
        assertTrue(samples.getValue(ProviderKind.FUSED).sample.timeMillis <
            samples.getValue(ProviderKind.GPS).sample.timeMillis)
    }

    @Test
    fun stationaryFixHasNoBearingOrSpeed() {
        val stationary = moving.copy(speed = 0f, bearing = 0f)
        mixer.mix(stationary).values.forEach { sample ->
            assertFalse(sample.has(ProviderFieldMask.BEARING))
            assertFalse(sample.has(ProviderFieldMask.SPEED))
            assertEquals(0f, sample.sample.speed, 0f)
            assertEquals(0f, sample.sample.bearing, 0f)
        }
    }

    @Test
    fun stationaryAccuracyDriftsButStaysBounded() {
        val stationary = moving.copy(speed = 0f)
        val samples = mixer.mix(stationary)
        samples.values.forEach { sample ->
            assertTrue(sample.sample.accuracy > sample.profile.accuracyMeters)
            assertTrue(sample.sample.accuracy <= sample.profile.accuracyMeters + 0.5f + 0.001f)
        }
    }

    @Test
    fun latencyNeverProducesNegativeTimestamps() {
        val early = moving.copy(timeMillis = 100L, elapsedNanos = 50L)
        val samples = mixer.mix(early)
        samples.values.forEach { sample ->
            assertTrue(sample.sample.timeMillis >= 0L)
            assertTrue(sample.sample.elapsedNanos >= 0L)
        }
        assertEquals(0L, samples.getValue(ProviderKind.NETWORK).sample.timeMillis)
        assertEquals(0L, samples.getValue(ProviderKind.FUSED).sample.timeMillis)
        assertEquals(100L, samples.getValue(ProviderKind.GPS).sample.timeMillis)
    }

    @Test
    fun standardMockPreservesConfiguredGpsAccuracyAndScalesOtherProviders() {
        val standard = ProviderMixer(useSampleAccuracy = true)
        for (speed in listOf(0f, 5f)) {
            val samples = standard.mix(moving.copy(accuracy = 2.5f, speed = speed))
            assertEquals(2.5f, samples.getValue(ProviderKind.GPS).sample.accuracy, 0f)
            assertEquals(25f, samples.getValue(ProviderKind.NETWORK).sample.accuracy, 0.001f)
            assertEquals(2.5f * 10f / 6f, samples.getValue(ProviderKind.FUSED).sample.accuracy, 0.001f)
        }
        assertEquals(10_000f, standard.mix(moving.copy(accuracy = 10_000f))
            .getValue(ProviderKind.NETWORK).sample.accuracy, 0f)
    }

    @Test
    fun invalidProfilesAreRejected() {
        assertThrows { ProviderProfile(ProviderKind.GPS, 0, 5f, 1f, 0, true, true, true) }
        assertThrows { ProviderProfile(ProviderKind.GPS, 1_000, -1f, 1f, 0, true, true, true) }
        assertThrows { StationaryPolicy(speedThresholdMps = -1.0) }
        assertThrows { ProviderMixer(listOf(ProviderProfile.GPS, ProviderProfile.GPS)) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
