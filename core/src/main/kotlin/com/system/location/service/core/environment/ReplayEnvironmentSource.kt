package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Replays a captured environment while re-calibrating RSSI for a new target point (P2-02).
 *
 * The capture has no AP coordinates in v1, so the replay treats the original observer origin as the
 * reference and applies a deterministic distance path-loss offset. Identities (BSSID/SSID/channel)
 * never change; only RSSI is adjusted and clamped to the legal range.
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
        val origin = captured.origin ?: point
        val distance = distanceMeters(origin, point)
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

    private fun distanceMeters(a: Wgs84, b: Wgs84): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val REFERENCE_METERS = 10.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
