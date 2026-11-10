package com.system.location.service.hook.utils

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.asin

/** Existing spherical movement model, separate from GNSS ellipsoidal observation geometry. */
internal object LocationGeometry {
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

    fun moveLocation(lat: Double, lon: Double, n: Double, angle: Double): Pair<Double, Double> {
        require(n.isFinite() && n >= 0 && angle.isFinite())
        if (n == 0.0) return lat to lon
        val distance = n / 6371000.0
        val latitudeRadians = Math.toRadians(lat)
        val direction = Math.toRadians(angle)
        val newLat = asin((sin(latitudeRadians) * cos(distance) + cos(latitudeRadians) * sin(distance) * cos(direction)).coerceIn(-1.0, 1.0))
        val newLon = Math.toRadians(lon) + atan2(sin(direction) * sin(distance) * cos(latitudeRadians), cos(distance) - sin(latitudeRadians) * sin(newLat))
        return Math.toDegrees(newLat) to ((Math.toDegrees(newLon) + 540) % 360 - 180)
    }

    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val lat1 = Math.toRadians(latA)
        val lon1 = Math.toRadians(lonA)
        val lat2 = Math.toRadians(latB)
        val lon2 = Math.toRadians(lonB)

        val deltaLon = lon2 - lon1

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360  // 标准化到0-360度

        return bearing
    }
}
