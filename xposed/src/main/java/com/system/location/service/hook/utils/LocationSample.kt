package com.system.location.service.hook.utils

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.location.LocationSanitizer
import com.system.location.service.core.gnss.GnssLocationExtras
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.hook.hooks.gnss.GnssReceiverRuntime

/** Android conversion derives GPS extras from the same provider sample as status and NMEA. */
fun LocationSample.toLocation(provider: String?, route: GnssFrameRoute = GnssFrameRoute.Global): Location {
    val sample = this
    val fields = LocationSanitizer.fieldsFor(sample)
    val effectiveProvider = provider ?: LocationManager.GPS_PROVIDER
    val publication = if (FakeLoc.enable && FakeLoc.enableMockGnss && effectiveProvider == LocationManager.GPS_PROVIDER)
        GnssReceiverRuntime.frameForSample(sample, route) else null
    return Location(effectiveProvider).also {
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
        // Ellipsoidal altitude cannot be reported as MSL without an observed/modelled geoid separation.
        it.elapsedRealtimeUncertaintyNanos = fields.elapsedRealtimeUncertaintyNanos.toDouble()
        it.extras = publication?.let { frame ->
            val summary = GnssLocationExtras.from(frame)
            Bundle().apply {
                putInt("satellites", summary.satellites)
                summary.meanCn0DbHz?.let { value -> putFloat("meanCn0", value) }
                summary.maxCn0DbHz?.let { value -> putFloat("maxCn0", value) }
            }
        }
        it.isMock = !FakeLoc.hideMock
    }
}
