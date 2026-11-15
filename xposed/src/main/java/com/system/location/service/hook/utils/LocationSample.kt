package com.system.location.service.hook.utils

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.location.LocationSanitizer
import com.system.location.service.core.gnss.GnssLocationExtras
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.hook.hooks.gnss.GnssReceiverRuntime
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.providers.ProviderFieldMask
import com.system.location.service.core.providers.ProviderSample

/** Android conversion derives GPS extras from the same provider sample as status and NMEA. */
fun LocationSample.toLocation(provider: String?, route: GnssFrameRoute = GnssFrameRoute.Global,
    state: RuntimeSnapshot = FakeLoc.runtimeSnapshots.current(), validationAtMillis: Long = System.currentTimeMillis(),
    providerSample: ProviderSample? = null): Location {
    val sample = this
    val fields = LocationSanitizer.fieldsFor(sample)
    val effectiveProvider = provider ?: LocationManager.GPS_PROVIDER
    val policy = state.configuration
    val mslAltitude = providerSample?.mslAltitudeMeters
    val publication = if (policy.enabled && policy.gnssEnabled && effectiveProvider == LocationManager.GPS_PROVIDER)
        GnssReceiverRuntime.frameForSample(sample, route, state, validationAtMillis) else null
    return Location(effectiveProvider).also {
        it.latitude = sample.latitude
        it.longitude = sample.longitude
        it.accuracy = sample.accuracy
        it.time = sample.timeMillis
        it.elapsedRealtimeNanos = sample.elapsedNanos
        fun has(mask: Int) = providerSample == null || providerSample.has(mask)
        if (has(ProviderFieldMask.ALTITUDE)) it.altitude = sample.altitude else it.removeAltitude()
        if (has(ProviderFieldMask.SPEED)) it.speed = sample.speed else it.removeSpeed()
        if (has(ProviderFieldMask.BEARING)) it.bearing = sample.bearing else it.removeBearing()
        if (has(ProviderFieldMask.VERTICAL_ACCURACY)) {
            it.verticalAccuracyMeters = providerSample?.verticalAccuracyMeters ?: fields.verticalAccuracyMeters
        } else it.removeVerticalAccuracy()
        if (has(ProviderFieldMask.SPEED_ACCURACY)) {
            it.speedAccuracyMetersPerSecond = providerSample?.speedAccuracyMps ?: fields.speedAccuracyMetersPerSecond
        } else it.removeSpeedAccuracy()
        if (has(ProviderFieldMask.BEARING_ACCURACY)) {
            it.bearingAccuracyDegrees = providerSample?.bearingAccuracyDeg ?: fields.bearingAccuracyDegrees
        } else it.removeBearingAccuracy()
        if (has(ProviderFieldMask.ELAPSED_REALTIME_UNCERTAINTY)) {
            it.elapsedRealtimeUncertaintyNanos =
                (providerSample?.elapsedRealtimeUncertaintyNanos ?: fields.elapsedRealtimeUncertaintyNanos).toDouble()
        } else it.removeElapsedRealtimeUncertaintyNanos()
        if (providerSample?.has(ProviderFieldMask.MSL_ALTITUDE) == true && mslAltitude != null) {
            it.mslAltitudeMeters = mslAltitude
        } else it.removeMslAltitude()
        it.extras = if (effectiveProvider == LocationManager.GPS_PROVIDER) {
            // A GPS fix without the standard satellite extras is itself a fingerprint. Keep the
            // shape even when GNSS simulation is disabled; zero means no satellites used.
            val summary = publication?.let(GnssLocationExtras::from)
            Bundle().apply {
                putInt("satellites", summary?.satellites ?: 0)
                putFloat("meanCn0", summary?.meanCn0DbHz ?: 0f)
                putFloat("maxCn0", summary?.maxCn0DbHz ?: 0f)
            }
        } else null
        it.isMock = !policy.hideMock
    }
}
