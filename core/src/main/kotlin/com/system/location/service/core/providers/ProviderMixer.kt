package com.system.location.service.core.providers

import com.system.location.service.core.location.LocationSample
import kotlinx.serialization.Serializable

enum class ProviderKind { GPS, NETWORK, FUSED }

/** Per-provider accuracy/frequency/field policy (P4-01, ADR-007). */
@Serializable
data class ProviderProfile(
    val kind: ProviderKind,
    val intervalMs: Long,
    val accuracyMeters: Float,
    val verticalAccuracyFactor: Float,
    val latencyMs: Long,
    val supportsBearing: Boolean,
    val supportsAltitude: Boolean,
    val supportsSpeed: Boolean,
    val accuracyDriftMeters: Float = 0f,
) {
    init {
        require(intervalMs in 100L..60_000L) { "intervalMs out of range: $intervalMs" }
        require(accuracyMeters > 0f) { "accuracyMeters must be positive" }
        require(verticalAccuracyFactor >= 1f) { "verticalAccuracyFactor must be >= 1" }
        require(latencyMs in 0L..30_000L) { "latencyMs out of range: $latencyMs" }
        require(accuracyDriftMeters >= 0f) { "accuracyDriftMeters must be non-negative" }
    }

    companion object {
        val GPS = ProviderProfile(
            kind = ProviderKind.GPS,
            intervalMs = 1_000,
            accuracyMeters = 6f,
            verticalAccuracyFactor = 2f,
            latencyMs = 0,
            supportsBearing = true,
            supportsAltitude = true,
            supportsSpeed = true,
            accuracyDriftMeters = 1.5f,
        )
        val NETWORK = ProviderProfile(
            kind = ProviderKind.NETWORK,
            intervalMs = 3_000,
            accuracyMeters = 60f,
            verticalAccuracyFactor = 2.5f,
            latencyMs = 400,
            supportsBearing = false,
            supportsAltitude = false,
            supportsSpeed = false,
            accuracyDriftMeters = 8f,
        )
        val FUSED = ProviderProfile(
            kind = ProviderKind.FUSED,
            intervalMs = 1_000,
            accuracyMeters = 10f,
            verticalAccuracyFactor = 1.8f,
            latencyMs = 120,
            supportsBearing = true,
            supportsAltitude = true,
            supportsSpeed = true,
            accuracyDriftMeters = 2.5f,
        )

        fun defaults(): List<ProviderProfile> = listOf(GPS, NETWORK, FUSED)
    }
}

@Serializable
data class StationaryPolicy(
    val speedThresholdMps: Double = 0.3,
    val bearingHoldSeconds: Double = 2.0,
    val accuracyDriftMeters: Float = 0.5f,
    val stepRatePerMin: Int = 0,
) {
    init {
        require(speedThresholdMps >= 0.0) { "speedThresholdMps must be non-negative" }
        require(bearingHoldSeconds >= 0.0) { "bearingHoldSeconds must be non-negative" }
        require(accuracyDriftMeters >= 0f) { "accuracyDriftMeters must be non-negative" }
        require(stepRatePerMin >= 0) { "stepRatePerMin must be non-negative" }
    }
}

object ProviderFieldMask {
    const val SPEED = 1 shl 0
    const val BEARING = 1 shl 1
    const val ALTITUDE = 1 shl 2
    const val MSL_ALTITUDE = 1 shl 3
    const val VERTICAL_ACCURACY = 1 shl 4
    const val SPEED_ACCURACY = 1 shl 5
    const val BEARING_ACCURACY = 1 shl 6
    const val ELAPSED_REALTIME_UNCERTAINTY = 1 shl 7
    const val SATELLITES = 1 shl 8
    const val CN0 = 1 shl 9
}

data class ProviderSample(
    val profile: ProviderProfile,
    val sample: LocationSample,
    val fieldsMask: Int,
    val verticalAccuracyMeters: Float?,
    val speedAccuracyMps: Float?,
    val bearingAccuracyDeg: Float?,
    val mslAltitudeMeters: Double?,
    val elapsedRealtimeUncertaintyNanos: Long?,
    val satellites: Int?,
    val meanCn0: Float?,
    val maxCn0: Float?,
) {
    init {
        require(fieldsMask >= 0) { "fieldsMask must be non-negative" }
    }

    fun has(mask: Int): Boolean = fieldsMask and mask != 0
}

/**
 * Splits one deterministic fix into provider-specific samples (P4-01).
 *
 * The mixer is stateless: scheduling is the runtime's job. It only guarantees that the three
 * provider views differ in accuracy, latency and field availability, and that a stationary fix
 * never exposes a 0° bearing or a fake speed.
 */
class ProviderMixer(
    private val profiles: List<ProviderProfile> = ProviderProfile.defaults(),
    private val stationaryPolicy: StationaryPolicy = StationaryPolicy(),
) {
    init {
        require(profiles.isNotEmpty()) { "profiles must not be empty" }
        require(profiles.map { it.kind }.distinct().size == profiles.size) { "duplicate provider kind" }
    }

    fun mix(
        sample: LocationSample,
        satellites: Int? = null,
        meanCn0: Float? = null,
        maxCn0: Float? = null,
    ): Map<ProviderKind, ProviderSample> {
        val moving = sample.speed > stationaryPolicy.speedThresholdMps
        return profiles.associate { profile ->
            val accuracy = if (moving) profile.accuracyMeters else {
                profile.accuracyMeters + stationaryPolicy.accuracyDriftMeters
            }
            val useBearing = moving && profile.supportsBearing && sample.speed > 0f
            val useSpeed = profile.supportsSpeed && moving
            val useAltitude = profile.supportsAltitude
            val latencyMillis = profile.latencyMs
            val timeMillis = (sample.timeMillis - latencyMillis).coerceAtLeast(0L)
            val elapsedNanos = (sample.elapsedNanos - latencyMillis * 1_000_000L).coerceAtLeast(0L)
            var mask = ProviderFieldMask.VERTICAL_ACCURACY or
                ProviderFieldMask.ELAPSED_REALTIME_UNCERTAINTY
            if (useSpeed) mask = mask or ProviderFieldMask.SPEED or ProviderFieldMask.SPEED_ACCURACY
            if (useBearing) mask = mask or ProviderFieldMask.BEARING or ProviderFieldMask.BEARING_ACCURACY
            if (useAltitude) mask = mask or ProviderFieldMask.ALTITUDE or ProviderFieldMask.MSL_ALTITUDE
            if (satellites != null) mask = mask or ProviderFieldMask.SATELLITES
            if (meanCn0 != null || maxCn0 != null) mask = mask or ProviderFieldMask.CN0

            val adjusted = sample.copy(
                timeMillis = timeMillis,
                elapsedNanos = elapsedNanos,
                accuracy = accuracy,
                speed = if (useSpeed) sample.speed else 0f,
                bearing = if (useBearing) sample.bearing else 0f,
                altitude = if (useAltitude) sample.altitude else 0.0,
            )
            profile.kind to ProviderSample(
                profile = profile,
                sample = adjusted,
                fieldsMask = mask,
                verticalAccuracyMeters = accuracy * profile.verticalAccuracyFactor,
                speedAccuracyMps = if (useSpeed) 0.15f else null,
                bearingAccuracyDeg = if (useBearing) 2f else null,
                mslAltitudeMeters = if (useAltitude) adjusted.altitude else null,
                elapsedRealtimeUncertaintyNanos = if (profile.kind == ProviderKind.NETWORK) 50_000_000L else 5_000_000L,
                satellites = if (profile.kind == ProviderKind.NETWORK) null else satellites,
                meanCn0 = if (profile.kind == ProviderKind.NETWORK) null else meanCn0,
                maxCn0 = if (profile.kind == ProviderKind.NETWORK) null else maxCn0,
            )
        }
    }
}
