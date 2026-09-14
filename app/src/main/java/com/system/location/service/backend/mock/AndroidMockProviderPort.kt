package com.system.location.service.backend.mock

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Process
import android.util.Log
import com.system.location.service.core.backend.BackendDiagnostic
import com.system.location.service.core.location.LocationSample
import com.system.location.service.ext.experimentalClearMockFlag

/** Standard mock APIs, with an opt-in local reflection experiment before submission. */
class AndroidMockProviderPort(context: Context) : MockProviderPort {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val journal = context.getSharedPreferences("mock_provider_ownership", Context.MODE_PRIVATE)
    private val mockFlagExperiment = MockFlagExperiment()
    @Volatile private var lastFlagOutcome: MockFlagExperiment.Outcome? = null
    private var experimentWasEnabled = false
    override fun diagnostics(): List<BackendDiagnostic> {
        val enabled = context.experimentalClearMockFlag
        val outcome = lastFlagOutcome
        return listOf(BackendDiagnostic("MOCK_FLAG_EXPERIMENT",
            if (!enabled) "DISABLED" else if (outcome == null) "PENDING" else if (outcome.invoked) "LOCAL_ONLY" else "UNAVAILABLE",
            if (!enabled) "提交前反射清除实验已关闭" else outcome?.detail ?: "实验已开启，等待定位提交",
            "Android 可能重新标记 mock；不能据此判断高德是否接受定位"))
    }
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
        val location = Location(provider).apply {
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
        }
        val enabled = context.experimentalClearMockFlag
        if (enabled) {
            val outcome = mockFlagExperiment.clear(location)
            if (!experimentWasEnabled || outcome != lastFlagOutcome) {
                Log.i("MockFlagExperiment", outcome.detail)
            }
            lastFlagOutcome = outcome
        } else {
            lastFlagOutcome = null
        }
        experimentWasEnabled = enabled
        manager.setTestProviderLocation(provider, location)
    }
    override fun remove(provider: String) {
        try { manager.removeTestProvider(provider) }
        catch (_: IllegalArgumentException) { /* Already removed by Android; cleanup is idempotent. */ }
        record(pendingProviders() - provider)
    }
}
