package com.system.location.service.environment

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import com.system.location.service.core.geo.Wgs84
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class EnvironmentCaptureOrigin(
    val coordinate: Wgs84,
    val accuracyMeters: Double,
    val elapsedRealtimeNanos: Long,
)

class EnvironmentCaptureLocation(private val context: Context) {
    @Suppress("MissingPermission")
    suspend fun current(): EnvironmentCaptureOrigin {
        val manager = context.getSystemService(LocationManager::class.java)
            ?: error("系统定位服务不可用")
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        check(providers.isNotEmpty()) { "请先开启系统定位" }

        for (provider in providers) {
            val location = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) { request(manager, provider) }
                ?: continue
            if (isUsable(location, SystemClock.elapsedRealtimeNanos())) {
                return EnvironmentCaptureOrigin(
                    Wgs84(location.latitude, location.longitude),
                    location.accuracy.toDouble(),
                    location.elapsedRealtimeNanos,
                )
            }
        }
        error("未取得新鲜、非模拟且带有效精度的定位，请移至信号良好处重试")
    }

    private suspend fun request(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (failure: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (failure: RuntimeException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun isUsable(location: Location, nowElapsedNanos: Long): Boolean {
        if (location.isMock || !location.hasAccuracy()) return false
        val accuracy = location.accuracy
        if (!accuracy.isFinite() || accuracy <= 0f) return false
        val ageNanos = nowElapsedNanos - location.elapsedRealtimeNanos
        return ageNanos in 0..MAX_FIX_AGE_NANOS
    }

    private companion object {
        const val LOCATION_TIMEOUT_MILLIS = 8_000L
        const val MAX_FIX_AGE_NANOS = 15_000_000_000L
    }
}
