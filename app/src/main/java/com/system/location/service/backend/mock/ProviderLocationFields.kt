package com.system.location.service.backend.mock

import android.location.Location
import android.os.Build
import com.system.location.service.core.providers.ProviderFieldMask
import com.system.location.service.core.providers.ProviderSample

/** A fresh Location keeps absent fields unset without calling newer platform removal APIs. */
internal fun ProviderSample.toLocation(provider: String): Location {
    val view = this
    val mslAltitudeMeters = view.mslAltitudeMeters
    return Location(provider).apply {
        latitude = view.sample.latitude
        longitude = view.sample.longitude
        accuracy = view.sample.accuracy
        time = view.sample.timeMillis
        elapsedRealtimeNanos = view.sample.elapsedNanos

        if (view.has(ProviderFieldMask.ALTITUDE)) altitude = view.sample.altitude
        if (view.has(ProviderFieldMask.SPEED)) speed = view.sample.speed
        if (view.has(ProviderFieldMask.BEARING)) bearing = view.sample.bearing

        if (view.has(ProviderFieldMask.VERTICAL_ACCURACY)) {
            verticalAccuracyMeters = view.verticalAccuracyMeters ?: view.sample.accuracy
        }
        if (view.has(ProviderFieldMask.SPEED_ACCURACY)) {
            speedAccuracyMetersPerSecond = view.speedAccuracyMps ?: 0f
        }
        if (view.has(ProviderFieldMask.BEARING_ACCURACY)) {
            bearingAccuracyDegrees = view.bearingAccuracyDeg ?: 0f
        }
        if (view.has(ProviderFieldMask.ELAPSED_REALTIME_UNCERTAINTY)) {
            elapsedRealtimeUncertaintyNanos = view.elapsedRealtimeUncertaintyNanos?.toDouble() ?: 0.0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            view.has(ProviderFieldMask.MSL_ALTITUDE) && mslAltitudeMeters != null) {
            this.mslAltitudeMeters = mslAltitudeMeters
        }
    }
}
