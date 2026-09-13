package com.system.location.service.hook.utils

import android.location.Location
import android.location.LocationManager

/** Immutable fix shared by every recipient of one simulation sample. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val timeMillis: Long,
    val elapsedNanos: Long,
) {
    fun toLocation(provider: String?): Location = Location(provider ?: LocationManager.GPS_PROVIDER).also {
        it.latitude = latitude
        it.longitude = longitude
        it.altitude = altitude
        it.accuracy = accuracy
        it.speed = speed
        it.bearing = bearing
        it.time = timeMillis
        it.elapsedRealtimeNanos = elapsedNanos
        it.speedAccuracyMetersPerSecond = 0.1f
        it.bearingAccuracyDegrees = 1f
        it.verticalAccuracyMeters = accuracy
        it.isMock = !FakeLoc.hideMock
    }
}
