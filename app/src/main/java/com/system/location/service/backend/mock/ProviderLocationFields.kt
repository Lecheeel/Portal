package com.system.location.service.backend.mock

import android.location.Location
import com.system.location.service.core.providers.ProviderFieldMask
import com.system.location.service.core.providers.ProviderSample

/** Applies the provider model's field presence and values to Android's Location contract. */
internal fun ProviderSample.applyTo(location: Location): Location {
    val view = this
    val mslAltitudeMeters = view.mslAltitudeMeters
    return location.apply {
        latitude = view.sample.latitude
        longitude = view.sample.longitude
        accuracy = view.sample.accuracy
        time = view.sample.timeMillis
        elapsedRealtimeNanos = view.sample.elapsedNanos

        if (view.has(ProviderFieldMask.ALTITUDE)) altitude = view.sample.altitude else removeAltitude()
        if (view.has(ProviderFieldMask.SPEED)) speed = view.sample.speed else removeSpeed()
        if (view.has(ProviderFieldMask.BEARING)) bearing = view.sample.bearing else removeBearing()

        if (view.has(ProviderFieldMask.VERTICAL_ACCURACY)) {
            verticalAccuracyMeters = view.verticalAccuracyMeters ?: view.sample.accuracy
        } else removeVerticalAccuracy()
        if (view.has(ProviderFieldMask.SPEED_ACCURACY)) {
            speedAccuracyMetersPerSecond = view.speedAccuracyMps ?: 0f
        } else removeSpeedAccuracy()
        if (view.has(ProviderFieldMask.BEARING_ACCURACY)) {
            bearingAccuracyDegrees = view.bearingAccuracyDeg ?: 0f
        } else removeBearingAccuracy()
        if (view.has(ProviderFieldMask.ELAPSED_REALTIME_UNCERTAINTY)) {
            elapsedRealtimeUncertaintyNanos = view.elapsedRealtimeUncertaintyNanos?.toDouble() ?: 0.0
        } else removeElapsedRealtimeUncertaintyNanos()
        if (view.has(ProviderFieldMask.MSL_ALTITUDE) && mslAltitudeMeters != null) {
            this.mslAltitudeMeters = mslAltitudeMeters
        } else removeMslAltitude()
    }
}
