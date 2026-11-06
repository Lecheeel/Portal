package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class WifiObservation(
    val bssid: String,
    val rssi: Int,
) {
    init {
        require(WifiFingerprint.BSSID_PATTERN.matches(bssid)) { "Invalid BSSID: $bssid" }
        require(rssi in WifiFingerprint.MIN_RSSI..WifiFingerprint.MAX_RSSI) { "rssi out of range: $rssi" }
    }
}

@Serializable
data class WifiFingerprint(
    val bssid: String,
    val position: Wgs84,
    val rssi: Int,
    val observedAtMillis: Long,
) {
    init {
        require(BSSID_PATTERN.matches(bssid)) { "Invalid BSSID: $bssid" }
        require(rssi in MIN_RSSI..MAX_RSSI) { "rssi out of range: $rssi" }
        require(observedAtMillis >= 0L) { "observedAtMillis must be non-negative" }
    }

    companion object {
        val BSSID_PATTERN = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\$")
        const val MIN_RSSI = -100
        const val MAX_RSSI = 0
    }
}

/**
 * Experimental WiFi fingerprint database (P2-06).
 *
 * Query matches observations to the closest stored RSSI per BSSID, then takes a distance-weighted
 * centroid. Stale fingerprints are ignored; there is no interpolation outside the stored samples.
 */
@Serializable
data class WifiFingerprintDb(
    val entries: List<WifiFingerprint> = emptyList(),
) {
    init {
        require(entries.size <= MAX_ENTRIES) { "Too many fingerprints: ${entries.size}" }
    }

    fun add(fingerprint: WifiFingerprint): WifiFingerprintDb =
        WifiFingerprintDb((entries + fingerprint).takeLast(MAX_ENTRIES))

    fun remove(bssid: String): WifiFingerprintDb =
        WifiFingerprintDb(entries.filterNot { it.bssid.equals(bssid, ignoreCase = true) })

    fun query(
        observations: List<WifiObservation>,
        nowMillis: Long,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): Wgs84? {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
        require(maxAgeMillis >= 0L) { "maxAgeMillis must be non-negative" }
        if (observations.isEmpty()) return null
        val matches = observations.mapNotNull { observation ->
            val fingerprint = entries
                .filter { it.bssid.equals(observation.bssid, ignoreCase = true) }
                .filter { nowMillis - it.observedAtMillis in 0..maxAgeMillis }
                .minByOrNull { abs(it.rssi - observation.rssi) }
                ?: return@mapNotNull null
            val weight = 1.0 / (abs(fingerprint.rssi - observation.rssi) + 1.0)
            fingerprint.position to weight
        }
        if (matches.isEmpty()) return null
        val total = matches.sumOf { it.second }
        if (total <= 0.0) return null
        val latitude = matches.sumOf { it.first.latitude * it.second } / total
        val longitude = matches.sumOf { it.first.longitude * it.second } / total
        return Wgs84(latitude.coerceIn(-90.0, 90.0), longitude.coerceIn(-180.0, 180.0))
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val DEFAULT_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
