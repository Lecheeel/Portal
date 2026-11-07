package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * P2-06: experimental fingerprint replay. Selects recent observations near the target point and
 * turns them into a deterministic WiFi profile; returns null when nothing is in range.
 */
class FingerprintWifiSource(
    private val database: WifiFingerprintDb,
    private val radiusMeters: Double = DEFAULT_RADIUS_METERS,
    private val maxAps: Int = DEFAULT_MAX_APS,
) {
    init {
        require(radiusMeters > 0.0 && radiusMeters.isFinite()) { "radiusMeters must be positive" }
        require(maxAps in 1..8) { "maxAps out of range" }
    }

    fun profileAt(
        point: Wgs84,
        seed: Long,
        nowMillis: Long,
        maxAgeMillis: Long = WifiFingerprintDb.DEFAULT_MAX_AGE_MILLIS,
    ): EnvironmentProfile? {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
        require(maxAgeMillis >= 0L) { "maxAgeMillis must be non-negative" }
        val candidates = database.entries
            .filter { nowMillis - it.observedAtMillis in 0..maxAgeMillis }
            .filter { distanceMeters(point, it.position) <= radiusMeters }
            .groupBy { it.bssid.uppercase() }
            .mapNotNull { (bssid, entries) -> entries.maxByOrNull { it.rssi }?.let { bssid to it.rssi } }
            .sortedByDescending { it.second }
            .take(maxAps)
        if (candidates.isEmpty()) return null
        val wifi = candidates.mapIndexed { index, (bssid, rssi) ->
            WifiAp(
                ssid = "FP-${bssid.takeLast(5).replace(":", "")}",
                bssid = bssid,
                rssi = rssi,
                frequencyMhz = 2412,
                channel = 1,
                connected = index == 0,
            )
        }
        return EnvironmentProfile(
            source = EnvironmentSourceType.REPLAY,
            origin = point,
            generatedAtMillis = nowMillis,
            seed = seed,
            wifi = wifi,
        )
    }

    private fun distanceMeters(a: Wgs84, b: Wgs84): Double {
        val radius = 6_371_000.0
        val phi1 = Math.toRadians(a.latitude)
        val phi2 = Math.toRadians(b.latitude)
        val dPhi = Math.toRadians(b.latitude - a.latitude)
        val dLambda = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return 2 * radius * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val DEFAULT_RADIUS_METERS = 500.0
        const val DEFAULT_MAX_APS = 8
    }
}
