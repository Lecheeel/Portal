package com.system.location.service.core.location

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable

/** One immutable WGS84 fix, independent of Android, providers, SDKs and UI. */
@Serializable data class LocationSample(val coordinate: Wgs84, val altitude: Double, val accuracy: Float,
    val speed: Float, val bearing: Float, val timeMillis: Long, val elapsedNanos: Long) {
    init {
        require(altitude.isFinite() && accuracy.isFinite() && accuracy > 0)
        require(speed.isFinite() && speed >= 0 && bearing.isFinite() && bearing >= 0 && bearing < 360)
        require(timeMillis >= 0 && elapsedNanos >= 0)
    }
    val latitude get() = coordinate.latitude
    val longitude get() = coordinate.longitude
}
