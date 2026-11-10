package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.playback.NoiseProfile
import com.system.location.service.core.playback.PhysicalNoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PhysicalNoiseTest {
    private val sample = LocationSample(
        coordinate = Wgs84(31.2304, 121.4737),
        altitude = 80.0,
        accuracy = 8f,
        speed = 5f,
        bearing = 90f,
        timeMillis = 1_790_000_000_000,
        elapsedNanos = 1_000_000_000,
    )

    @Test
    fun disabledNoiseIsExactIdentity() {
        val noise = PhysicalNoise(NoiseProfile(enabled = false))
        val output = noise.apply(sample, 1_000_000_000)
        assertEquals(sample, output)
    }

    @Test
    fun sameSeedAndTimestampSequenceIsReproducible() {
        val first = PhysicalNoise(NoiseProfile(seed = 42))
        val second = PhysicalNoise(NoiseProfile(seed = 42))
        val firstOutputs = timestamps().map { first.apply(sample, it) }
        val secondOutputs = timestamps().map { second.apply(sample, it) }
        assertEquals(firstOutputs, secondOutputs)
    }

    @Test
    fun differentSeedChangesDrift() {
        val first = PhysicalNoise(NoiseProfile(seed = 1))
        val second = PhysicalNoise(NoiseProfile(seed = 2))
        val firstOutput = timestamps().map { first.apply(sample, it) }.last()
        val secondOutput = timestamps().map { second.apply(sample, it) }.last()
        assertNotEquals(firstOutput.coordinate, secondOutput.coordinate)
    }

    @Test
    fun driftIsBoundedAndFinite() {
        val profile = NoiseProfile(seed = 7, maxOffsetMeters = 4.0)
        val noise = PhysicalNoise(profile)
        timestamps(seconds = 300).map { noise.apply(sample, it) }.forEach { output ->
            assertTrue(output.latitude in -90.0..90.0)
            assertTrue(output.longitude in -180.0..180.0)
            assertTrue(output.altitude.isFinite())
            assertTrue(output.accuracy.isFinite() && output.accuracy > 0f)
            val metersLat = (output.latitude - sample.latitude) * 111_320.0
            val metersLon = (output.longitude - sample.longitude) * 111_320.0 * kotlin.math.cos(Math.toRadians(sample.latitude))
            assertTrue(sqrt(metersLat * metersLat + metersLon * metersLon) <= profile.maxOffsetMeters * 1.5)
        }
    }

    @Test
    fun resetRestartsTheDeterministicSequence() {
        val noise = PhysicalNoise(NoiseProfile(seed = 9))
        val before = timestamps().map { noise.apply(sample, it) }
        noise.reset()
        val after = timestamps().map { noise.apply(sample, it) }
        assertEquals(before, after)
    }

    @Test fun repeatedReadersOfOneTimestampReceiveTheSameDrift() {
        val noise = PhysicalNoise(NoiseProfile(seed = 42))
        assertEquals(sample, noise.apply(sample, 1_000_000_000))
        assertEquals(sample, noise.apply(sample, 1_000_000_000))
        val output = noise.apply(sample, 2_000_000_000)
        assertNotEquals(sample.coordinate, output.coordinate)
        repeat(10) { assertEquals(output, noise.apply(sample, 2_000_000_000)) }
    }

    @Test fun delayedReaderCannotChangeTheNextNoiseStep() {
        val first = PhysicalNoise(NoiseProfile(seed = 42))
        val reference = PhysicalNoise(NoiseProfile(seed = 42))
        for (time in listOf(1_000_000_000L, 2_000_000_000L)) {
            assertEquals(reference.apply(sample, time), first.apply(sample, time))
        }
        assertEquals(sample, first.apply(sample, 1_000_000_000))
        assertEquals(reference.apply(sample, 3_000_000_000), first.apply(sample, 3_000_000_000))
    }

    private fun timestamps(seconds: Int = 60): List<Long> =
        (1..seconds).map { it * 1_000_000_000L }
}
