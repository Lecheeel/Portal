package com.system.location.service.core.location

import com.system.location.service.core.geo.Wgs84
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Reconstructs a provider batch from one synthetic endpoint without retaining real fixes. */
object LocationBatchTimeline {
    const val MAX_BATCH_SIZE = 256
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun synthesize(endpoint: LocationSample, count: Int, intervalMillis: Long): List<LocationSample> {
        require(count in 0..MAX_BATCH_SIZE) { "batch size out of range" }
        require(intervalMillis in 1L..60_000L) { "intervalMillis out of range" }
        if (count == 0) return emptyList()
        if (count == 1) return listOf(endpoint)

        val intervals = count - 1
        val elapsedLimitMillis = endpoint.elapsedNanos / 1_000_000L / intervals
        val interval = minOf(intervalMillis, endpoint.timeMillis / intervals, elapsedLimitMillis)
        if (interval <= 0L) return listOf(endpoint)

        return List(count) { index ->
            val stepsBack = (count - index - 1).toLong()
            val offsetMillis = interval * stepsBack
            val offsetSeconds = offsetMillis / 1_000.0
            val distanceMeters = endpoint.speed.toDouble() * offsetSeconds
            val coordinate = if (distanceMeters == 0.0) endpoint.coordinate else {
                val destination = destination(
                    endpoint.coordinate,
                    distanceMeters,
                    (endpoint.bearing + 180.0) % 360.0,
                )
                destination
            }
            endpoint.copy(
                coordinate = coordinate,
                timeMillis = endpoint.timeMillis - offsetMillis,
                elapsedNanos = endpoint.elapsedNanos - offsetMillis * 1_000_000L,
            )
        }
    }

    private fun destination(start: Wgs84, distanceMeters: Double, bearingDegrees: Double): Wgs84 {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val bearing = Math.toRadians(bearingDegrees)
        val resultLatitude = asin(
            (sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing)).coerceIn(-1.0, 1.0),
        )
        val resultLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(resultLatitude),
        )
        val normalizedLongitude = (Math.toDegrees(resultLongitude) + 540.0) % 360.0 - 180.0
        return Wgs84(Math.toDegrees(resultLatitude), normalizedLongitude)
    }
}
