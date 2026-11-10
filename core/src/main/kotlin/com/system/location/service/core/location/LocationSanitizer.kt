package com.system.location.service.core.location

/**
 * Platform-independent defaults for a sanitized fix (P1-01).
 *
 * The Android adapter applies these values to `android.location.Location`; keeping the policy here
 * makes every derivation testable on the JVM and gives the provider profiles (P4-01) one place to
 * override.
 */
data class LocationFieldPolicy(
    val verticalAccuracyFactor: Float = 1.8f,
    val speedAccuracyMps: Float = 0.15f,
    val bearingAccuracyDegrees: Float = 2.0f,
    val elapsedRealtimeUncertaintyNanos: Long = 5_000_000L,
) {
    init {
        require(verticalAccuracyFactor > 0f) { "verticalAccuracyFactor must be positive" }
        require(speedAccuracyMps >= 0f) { "speedAccuracyMps must be non-negative" }
        require(bearingAccuracyDegrees >= 0f) { "bearingAccuracyDegrees must be non-negative" }
        require(elapsedRealtimeUncertaintyNanos >= 0L) { "elapsedRealtimeUncertaintyNanos must be non-negative" }
    }
}

/** Values the Android adapter writes onto the `Location` object. */
data class SanitizedLocationFields(
    val verticalAccuracyMeters: Float,
    val speedAccuracyMetersPerSecond: Float,
    val bearingAccuracyDegrees: Float,
    val elapsedRealtimeUncertaintyNanos: Long,
)

/**
 * Derives missing fields without injecting randomness.
 *
 * - vertical accuracy is always worse than horizontal accuracy;
 * - speed/bearing accuracy are only present when the corresponding field is meaningful.
 * GNSS extras belong to the receiver frame; MSL altitude requires a geoid model.
 */
object LocationSanitizer {

    fun fieldsFor(
        sample: LocationSample,
        policy: LocationFieldPolicy = LocationFieldPolicy(),
    ): SanitizedLocationFields {
        val horizontal = sanitizeAccuracy(sample.accuracy, FALLBACK_HORIZONTAL_ACCURACY)
        val vertical = sanitizeAccuracy(horizontal * policy.verticalAccuracyFactor, horizontal * policy.verticalAccuracyFactor)
        val moving = sample.speed > STATIONARY_SPEED_MPS
        val speedAccuracy = if (sample.speed > 0f) {
            minOf(policy.speedAccuracyMps, maxOf(MIN_SPEED_ACCURACY_MPS, sample.speed * SPEED_ACCURACY_FRACTION))
        } else {
            0f
        }
        val bearingAccuracy = if (moving) policy.bearingAccuracyDegrees else 0f
        return SanitizedLocationFields(
            verticalAccuracyMeters = vertical,
            speedAccuracyMetersPerSecond = speedAccuracy,
            bearingAccuracyDegrees = bearingAccuracy,
            elapsedRealtimeUncertaintyNanos = sanitizeUncertainty(policy.elapsedRealtimeUncertaintyNanos),
        )
    }

    fun sanitizeAccuracy(value: Float, fallback: Float): Float =
        if (value.isFinite() && value > 0f) value else fallback

    fun sanitizeAltitude(value: Double): Double =
        if (value.isFinite() && value in MIN_ALTITUDE_METERS..MAX_ALTITUDE_METERS) value else FALLBACK_ALTITUDE_METERS

    fun sanitizeUncertainty(value: Long?): Long =
        if (value == null || value < 0L || value > MAX_UNCERTAINTY_NANOS) DEFAULT_UNCERTAINTY_NANOS else value

    fun sanitizeExtras(values: Map<String, Any?>): Map<String, Any> = buildMap {
        (values["satellites"] as? Int)?.let { if (it >= 0) put("satellites", it) }
        (values["meanCn0"] as? Float)?.let { if (it.isFinite() && it >= 0f) put("meanCn0", it) }
        (values["maxCn0"] as? Float)?.let { if (it.isFinite() && it >= 0f) put("maxCn0", it) }
    }

    const val STATIONARY_SPEED_MPS = 0.3f
    const val FALLBACK_HORIZONTAL_ACCURACY = 25f
    const val FALLBACK_ALTITUDE_METERS = 0.0
    const val DEFAULT_UNCERTAINTY_NANOS = 5_000_000L
    const val MAX_UNCERTAINTY_NANOS = 999_000_000L
    private const val MIN_SPEED_ACCURACY_MPS = 0.05f
    private const val SPEED_ACCURACY_FRACTION = 0.1f
    private const val MIN_ALTITUDE_METERS = -500.0
    private const val MAX_ALTITUDE_METERS = 20_000.0
}
