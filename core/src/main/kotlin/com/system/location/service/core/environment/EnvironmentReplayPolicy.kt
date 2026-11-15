package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A captured radio profile is usable only within its measured location uncertainty. */
object EnvironmentReplayPolicy {
    const val MIN_RADIUS_METERS = 25.0

    fun isApplicable(profile: EnvironmentProfile, point: Wgs84): Boolean {
        val origin = profile.origin ?: return false
        val accuracy = profile.originAccuracyMeters?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        return distanceMeters(origin, point) <= maxOf(MIN_RADIUS_METERS, accuracy * 2.0)
    }

    fun distanceMeters(a: Wgs84, b: Wgs84): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
