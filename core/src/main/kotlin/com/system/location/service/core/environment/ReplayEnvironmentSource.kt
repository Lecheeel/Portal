package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlin.math.ln

/**
 * Replays a captured environment while re-calibrating RSSI for a new target point (P2-02).
 *
 * Captured identities are only applicable near the measured origin. Outside its accuracy-derived
 * radius, callers must select a procedural source instead of moving the captured fingerprint.
 */
class ReplayEnvironmentSource(
    private val captured: EnvironmentProfile,
    private val pathLossExponent: Double = 2.0,
) : EnvironmentSource {
    init {
        require(captured.source == EnvironmentSourceType.REPLAY) { "captured profile must be REPLAY" }
        require(pathLossExponent > 0.0) { "pathLossExponent must be positive" }
    }

    override fun profileAt(point: Wgs84, seed: Long, atMillis: Long): EnvironmentProfile {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        require(EnvironmentReplayPolicy.isApplicable(captured, point)) {
            "Captured environment has no reliable origin or target is outside its measured range"
        }
        val origin = checkNotNull(captured.origin)
        val distance = EnvironmentReplayPolicy.distanceMeters(origin, point)
        val deltaDb = if (distance <= REFERENCE_METERS) 0.0 else {
            -10.0 * pathLossExponent * ln((distance + REFERENCE_METERS) / REFERENCE_METERS) / ln(10.0)
        }
        return captured.copy(
            origin = point,
            generatedAtMillis = atMillis,
            seed = seed,
            wifi = captured.wifi.map { ap ->
                ap.copy(rssi = (ap.rssi + deltaDb).toInt().coerceIn(WifiAp.MIN_RSSI, WifiAp.MAX_RSSI))
            },
        )
    }

    private companion object {
        const val REFERENCE_METERS = 10.0
    }
}
