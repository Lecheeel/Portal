package com.system.location.service.backend.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Best-effort physical fixes, not a cache purge. A new session cancels every old callback. */
class AndroidPhysicalLocationRecovery(
    context: Context,
    private val scope: CoroutineScope,
    private val report: suspend (String, String) -> Unit,
) : PhysicalLocationRecovery {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var job: Job? = null

    @Synchronized override fun cancel() { job?.cancel(); job = null }

    @Synchronized override fun request() {
        cancel()
        job = scope.launch {
            val since = SystemClock.elapsedRealtimeNanos()
            val outcomes = supervisorScope {
                listOf("gps", "network").map { provider -> async { recover(provider, since) } }.awaitAll()
            }
            ensureActive()
            report(if (outcomes.any { it.endsWith(":FRESH") }) "SUCCESS" else "UNKNOWN",
                "停止后真实定位请求：${outcomes.joinToString()}；不代表其他应用缓存已刷新")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recover(provider: String, since: Long): String = withContext(Dispatchers.Main.immediate) {
        val fixes = Channel<Location>(Channel.CONFLATED)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.isMock && location.elapsedRealtimeNanos >= since) fixes.trySend(location)
            }
        }
        try {
            if (!manager.isProviderEnabled(provider)) return@withContext "$provider:DISABLED"
            manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            val fix = withTimeoutOrNull(10_000) { fixes.receive() }
            "$provider:${if (fix == null) "TIMEOUT" else "FRESH"}"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            "$provider:NO_PERMISSION"
        } catch (_: IllegalArgumentException) {
            "$provider:UNAVAILABLE"
        } catch (error: RuntimeException) {
            "$provider:ERROR_${error.javaClass.simpleName}"
        } finally {
            runCatching { manager.removeUpdates(listener) }
            fixes.close()
        }
    }
}
