package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/**
 * Deterministic procedural WiFi environment (P2-03, ADR-004/022).
 *
 * The world is split into ~100 m cells. Each `(cell, seed)` generates the same fixed set of APs
 * with fixed positions; moving the observer only changes the distance-derived RSSI, so nearby
 * samples are smooth and no `Random()` state leaks between calls.
 */
class ProceduralWifiSource(
    private val config: EnvironmentConfig = EnvironmentConfig(),
) : EnvironmentSource {

    override fun profileAt(point: Wgs84, seed: Long, atMillis: Long): EnvironmentProfile {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        val latIndex = floor(point.latitude / LAT_STEP_METERS).toLong()
        val lonStep = longitudeStep(point.latitude)
        val lonIndex = floor(point.longitude / lonStep).toLong()

        val candidates = (0 until config.apPerCell).map { index ->
            val rng = SplitMix64(cellSeed(seed, latIndex, lonIndex, index))
            val apLat = latIndex * LAT_STEP_METERS + rng.nextDouble() * LAT_STEP_METERS
            val apLon = lonIndex * lonStep + rng.nextDouble() * lonStep
            val distance = distanceMeters(point, apLat, apLon)
            val channel = CHANNELS[rng.nextInt(CHANNELS.size)]
            val oui = OUIS[rng.nextInt(OUIS.size)]
            val bssid = "$oui:${hex(rng.nextInt(256))}:${hex(rng.nextInt(256))}:${hex(rng.nextInt(256))}"
            val ssid = SSIDS[rng.nextInt(SSIDS.size)] + "-" + hex(rng.nextInt(256)) + hex(rng.nextInt(256))
            val rssi = rssiForDistance(distance, rng.nextDouble())
            Candidate(
                WifiAp(
                    ssid = ssid,
                    bssid = bssid,
                    rssi = rssi,
                    frequencyMhz = channel.first,
                    channel = channel.second,
                ),
                distance,
            )
        }
        val connectedIndex = candidates.indices.minByOrNull { candidates[it].distance } ?: 0
        val wifi = candidates.mapIndexed { index, candidate ->
            candidate.ap.copy(connected = index == connectedIndex)
        }
        return EnvironmentProfile(
            source = EnvironmentSourceType.PROCEDURAL,
            origin = point,
            generatedAtMillis = atMillis,
            seed = seed,
            wifi = wifi,
        )
    }

    private data class Candidate(val ap: WifiAp, val distance: Double)

    private fun rssiForDistance(distanceMeters: Double, jitter01: Double): Int {
        val base = -30.0 - PATH_LOSS_EXPONENT * log10(max(1.0, distanceMeters))
        val jitter = jitter01 * JITTER_DB * 2.0 - JITTER_DB
        return (base + jitter).toInt().coerceIn(MIN_RSSI, MAX_RSSI)
    }

    private fun distanceMeters(point: Wgs84, latitude: Double, longitude: Double): Double {
        val dy = (latitude - point.latitude) * METERS_PER_DEGREE_LAT
        val dx = (longitude - point.longitude) * METERS_PER_DEGREE_LAT * cos(Math.toRadians(point.latitude))
        return kotlin.math.sqrt(dy * dy + dx * dx)
    }

    private fun longitudeStep(latitude: Double): Double =
        LAT_STEP_METERS / cos(Math.toRadians(latitude)).coerceAtLeast(MIN_COS)

    private fun cellSeed(seed: Long, latIndex: Long, lonIndex: Long, index: Int): Long {
        var hash = seed
        hash = hash * 31 + latIndex
        hash = hash * 31 + lonIndex
        hash = hash * 31 + index
        hash = hash xor (hash ushr 33)
        return hash * -0x61c8864680b583ebL
    }

    private fun hex(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

    private class SplitMix64(seed: Long) {
        private var state = seed

        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()

        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
    }

    private companion object {
        const val LAT_STEP_METERS = 0.0009
        const val METERS_PER_DEGREE_LAT = 110_540.0
        const val MIN_COS = 0.2
        const val PATH_LOSS_EXPONENT = 2.7
        const val JITTER_DB = 3.0
        const val MIN_RSSI = -92
        const val MAX_RSSI = -30

        val CHANNELS = listOf(
            2412 to 1,
            2437 to 6,
            2462 to 11,
            5180 to 36,
            5200 to 40,
            5745 to 149,
            5765 to 153,
        )

        val OUIS = listOf(
            "A4:50:46",
            "00:1A:11",
            "F0:9F:C2",
            "AC:84:C6",
            "D8:47:32",
            "B8:27:EB",
            "00:0C:29",
            "3C:5A:B4",
        )

        val SSIDS = listOf(
            "ChinaNet",
            "CMCC",
            "TP-LINK",
            "Xiaomi",
            "HUAWEI",
            "PortalLab",
            "Starbucks",
            "iPhone",
        )
    }
}
