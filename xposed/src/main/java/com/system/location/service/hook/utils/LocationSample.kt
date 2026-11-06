package com.system.location.service.hook.utils

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.location.LocationSanitizer

/**
 * Android conversion stays outside the shared immutable domain sample.
 *
 * P1-01: fill the fields a location provider is expected to expose (vertical/speed/bearing
 * accuracy, MSL altitude, elapsed-realtime uncertainty, satellite extras) instead of leaving them
 * at 0 / null. Values are derived deterministically from the sample; the random/noise layer is P4.
 */
fun LocationSample.toLocation(provider: String?): Location {
    val sample = this
    val fields = LocationSanitizer.fieldsFor(sample)
    return Location(provider ?: LocationManager.GPS_PROVIDER).also {
        it.latitude = sample.latitude
        it.longitude = sample.longitude
        it.altitude = sample.altitude
        it.accuracy = sample.accuracy
        it.speed = sample.speed
        it.bearing = sample.bearing
        it.time = sample.timeMillis
        it.elapsedRealtimeNanos = sample.elapsedNanos
        it.verticalAccuracyMeters = fields.verticalAccuracyMeters
        it.speedAccuracyMetersPerSecond = fields.speedAccuracyMetersPerSecond
        it.bearingAccuracyDegrees = fields.bearingAccuracyDegrees
        // MSL altitude APIs exist only on Android 14 (API 34) and later.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            it.mslAltitudeMeters = fields.mslAltitudeMeters
            it.mslAltitudeAccuracyMeters = fields.mslAltitudeAccuracyMeters
        }
        it.elapsedRealtimeUncertaintyNanos = fields.elapsedRealtimeUncertaintyNanos.toDouble()
        it.extras = Bundle().apply {
            putInt("satellites", fields.satellites)
            putFloat("meanCn0", fields.meanCn0DbHz)
            putFloat("maxCn0", fields.maxCn0DbHz)
        }
        it.isMock = !FakeLoc.hideMock
    }
}
