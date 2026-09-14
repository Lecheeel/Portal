package com.system.location.service.hook.utils

import android.location.Location
import android.location.LocationManager
import com.system.location.service.core.location.LocationSample

/** Android conversion stays outside the shared immutable domain sample. */
fun LocationSample.toLocation(provider: String?): Location = Location(provider ?: LocationManager.GPS_PROVIDER).also {
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
