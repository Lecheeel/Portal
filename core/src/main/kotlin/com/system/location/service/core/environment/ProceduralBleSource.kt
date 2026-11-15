package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/** Deterministic destination beacons with internally consistent advertisement records. */
class ProceduralBleSource {
    fun beaconsAt(point: Wgs84, seed: Long): List<BleBeacon> {
        val latCell = floor(point.latitude / CELL_DEGREES).toLong()
        val lonCell = floor(point.longitude / longitudeCell(point.latitude)).toLong()
        return (0 until BEACON_COUNT).map { index ->
            val random = Random64(cellSeed(seed, latCell, lonCell, index))
            val latitude = (latCell + random.nextDouble()) * CELL_DEGREES
            val longitude = (lonCell + random.nextDouble()) * longitudeCell(point.latitude)
            val distance = distanceMeters(point, latitude, longitude)
            val name = "Beacon-${hex(random.nextInt(256))}${hex(random.nextInt(256))}"
            val txPower = -59
            val rssi = (-35.0 - PATH_LOSS_EXPONENT * log10(max(1.0, distance)) + random.nextDouble() * JITTER_DB)
                .toInt().coerceIn(MIN_RSSI, MAX_RSSI)
            val address = buildString {
                append("02")
                repeat(5) { append(':').append(hex(random.nextInt(256))) }
            }
            BleBeacon(
                address = address,
                name = name,
                rssi = rssi,
                scanRecordHex = advertisement(name, txPower),
                txPowerDbm = txPower,
            )
        }
    }

    private fun advertisement(name: String, txPower: Int): String {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(
            2, 0x01, 0x06,
            2, 0x0A, txPower.toByte(),
            (nameBytes.size + 1).toByte(), 0x09,
        ) + nameBytes
        return bytes.joinToString(separator = "") { hex(it.toInt() and 0xff) }
    }

    private fun distanceMeters(point: Wgs84, latitude: Double, longitude: Double): Double {
        val dy = (latitude - point.latitude) * METERS_PER_DEGREE
        val dx = (longitude - point.longitude) * METERS_PER_DEGREE * cos(Math.toRadians(point.latitude))
        return kotlin.math.sqrt(dy * dy + dx * dx)
    }

    private fun longitudeCell(latitude: Double): Double =
        CELL_DEGREES / cos(Math.toRadians(latitude)).coerceAtLeast(MIN_COS)

    private fun cellSeed(seed: Long, latitude: Long, longitude: Long, index: Int): Long =
        (seed * 31 + latitude) * 31 + longitude * 31 + index

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0')

    private class Random64(seed: Long) {
        private var state = seed

        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var value = state
            value = (value xor (value ushr 30)) * -0x40a7b892e31b1a47L
            value = (value xor (value ushr 27)) * -0x6b2fb644ecceee15L
            return value xor (value ushr 31)
        }

        fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
    }

    private companion object {
        const val CELL_DEGREES = 0.0007
        const val METERS_PER_DEGREE = 110_540.0
        const val MIN_COS = 0.2
        const val PATH_LOSS_EXPONENT = 2.1
        const val JITTER_DB = 8.0
        const val MIN_RSSI = -95
        const val MAX_RSSI = -35
        const val BEACON_COUNT = 6
    }
}
