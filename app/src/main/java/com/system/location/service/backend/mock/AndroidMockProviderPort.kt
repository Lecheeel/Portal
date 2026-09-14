package com.system.location.service.backend.mock

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Process
import com.system.location.service.core.location.LocationSample

/** Uses only public Android mock-location APIs. Never strips the platform's mock flag. */
class AndroidMockProviderPort(context: Context) : MockProviderPort {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val journal = context.getSharedPreferences("mock_provider_ownership", Context.MODE_PRIVATE)
    override fun permissionGranted() = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    override fun pendingProviders(): Set<String> = journal.getStringSet("providers", emptySet())!!.toSet()
    private fun record(providers: Set<String>) {
        check(journal.edit().putStringSet("providers", providers).commit()) { "无法保存 Provider 清理记录" }
    }
    override fun register(provider: String) {
        record(pendingProviders() + provider)
        manager.addTestProvider(provider, ProviderProperties.Builder()
            .setHasNetworkRequirement(provider == LocationManager.NETWORK_PROVIDER)
            .setHasSatelliteRequirement(provider == LocationManager.GPS_PROVIDER)
            .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_FINE).build())
    }
    override fun enable(provider: String, enabled: Boolean) = manager.setTestProviderEnabled(provider, enabled)
    override fun publish(provider: String, sample: LocationSample) {
        manager.setTestProviderLocation(provider, Location(provider).apply {
            latitude = sample.latitude
            longitude = sample.longitude
            altitude = sample.altitude
            accuracy = sample.accuracy
            speed = sample.speed
            bearing = sample.bearing
            time = sample.timeMillis
            elapsedRealtimeNanos = sample.elapsedNanos
            verticalAccuracyMeters = sample.accuracy
            speedAccuracyMetersPerSecond = 0.1f
            bearingAccuracyDegrees = 1f
        })
    }
    override fun remove(provider: String) {
        try { manager.removeTestProvider(provider) }
        catch (_: IllegalArgumentException) { /* Already removed by Android; cleanup is idempotent. */ }
        record(pendingProviders() - provider)
    }
}
