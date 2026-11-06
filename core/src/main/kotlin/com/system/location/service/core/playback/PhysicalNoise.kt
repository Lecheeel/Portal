package com.system.location.service.core.playback

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

@Serializable
data class NoiseProfile(
    val enabled: Boolean = true,
    val ouAlphaPerSec: Double = 0.05,
    val ouSigmaMeters: Double = 0.8,
    val maxOffsetMeters: Double = 4.0,
    val altitudeAlpha: Double = 0.04,
    val altitudeSigmaMeters: Double = 0.45,
    val accuracyDriftMeters: Double = 1.0,
    val seed: Long = 0L,
) {
    init {
        require(ouAlphaPerSec > 0.0) { "ouAlphaPerSec must be positive" }
        require(ouSigmaMeters >= 0.0) { "ouSigmaMeters must be non-negative" }
        require(maxOffsetMeters > 0.0) { "maxOffsetMeters must be positive" }
        require(altitudeAlpha > 0.0) { "altitudeAlpha must be positive" }
        require(altitudeSigmaMeters >= 0.0) { "altitudeSigmaMeters must be non-negative" }
        require(accuracyDriftMeters >= 0.0) { "accuracyDriftMeters must be non-negative" }
    }
}

/**
 * Optional decorative noise layer (P4-02, ADR-008).
 *
 * `PlaybackEngine` stays deterministic; this class adds bounded, reproducible OU drift on top.
 * Disabled means the input sample is returned unchanged. Seed + the same timestamp sequence
 * reproduces exactly the same outputs; there is no wall-clock or global RNG.
 */
class PhysicalNoise(
    private val profile: NoiseProfile = NoiseProfile(),
    private val seed: Long = profile.seed,
) {
    private var random = SplitMix64(seed)
    private var lastNanos: Long? = null
    private var x = 0.0
    private var y = 0.0
    private var altitude = 0.0

    fun reset() {
        lastNanos = null
        x = 0.0
        y = 0.0
        altitude = 0.0
        random = SplitMix64(seed)
    }

    fun apply(sample: LocationSample, elapsedRealtimeNanos: Long): LocationSample {
        if (!profile.enabled) return sample
        if (elapsedRealtimeNanos < 0L) return sample
        val previous = lastNanos
        lastNanos = elapsedRealtimeNanos
        if (previous == null) return sample
        val dt = (elapsedRealtimeNanos - previous) / 1_000_000_000.0
        if (dt <= 0.0) return sample

        x = update(x, dt, profile.ouAlphaPerSec, profile.ouSigmaMeters, profile.maxOffsetMeters)
        y = update(y, dt, profile.ouAlphaPerSec, profile.ouSigmaMeters, profile.maxOffsetMeters)
        altitude = update(altitude, dt, profile.altitudeAlpha, profile.altitudeSigmaMeters, profile.maxOffsetMeters)
        if (!x.isFinite() || !y.isFinite() || !altitude.isFinite()) {
            reset()
            return sample
        }

        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = metersPerDegreeLat * cos(Math.toRadians(sample.latitude)).coerceAtLeast(0.2)
        val coordinate = Wgs84(
            (sample.latitude + y / metersPerDegreeLat).coerceIn(-90.0, 90.0),
            (sample.longitude + x / metersPerDegreeLon).coerceIn(-180.0, 180.0),
        )
        val accuracy = (sample.accuracy +
            (abs(x) + abs(y)).toFloat() * 0.08f +
            profile.accuracyDriftMeters.toFloat() * 0.1f).coerceAtLeast(sample.accuracy)
        return sample.copy(
            coordinate = coordinate,
            altitude = (sample.altitude + altitude).coerceIn(-500.0, 20_000.0),
            accuracy = accuracy,
        )
    }

    private fun update(value: Double, dt: Double, alpha: Double, sigma: Double, limit: Double): Double {
        val decay = exp(-alpha * dt)
        val scale = sigma * sqrt((1.0 - decay * decay).coerceIn(0.0, 1.0))
        val next = value * decay + scale * random.nextGaussian()
        return next.coerceIn(-limit, limit)
    }

    private class SplitMix64(seed: Long) {
        private var state = seed
        private var spare: Double? = null

        fun nextGaussian(): Double {
            spare?.let { spare = null; return it }
            var u1: Double
            do {
                u1 = nextDouble()
            } while (u1 <= 0.0)
            val u2 = nextDouble()
            val radius = sqrt(-2.0 * ln(u1))
            val theta = 2.0 * PI * u2
            spare = radius * kotlin.math.sin(theta)
            return radius * kotlin.math.cos(theta)
        }

        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
