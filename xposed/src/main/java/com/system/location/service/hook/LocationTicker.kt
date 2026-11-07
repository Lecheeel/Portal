package com.system.location.service.hook

import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One system-side publisher; delivery continues when the UI is backgrounded. */
object LocationTicker {
    private var executor: ScheduledExecutorService? = null
    private var future: ScheduledFuture<*>? = null

    private fun executor(): ScheduledExecutorService {
        executor?.let { return it }
        return Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "LocationPublisher").apply { isDaemon = true }
        }.also { executor = it }
    }

    @Synchronized
    fun start() {
        future?.cancel(false)
        val interval = FakeLoc.reportIntervalMs.coerceIn(50, 1000)
        future = executor().scheduleWithFixedDelay({
            runCatching {
                if (RemoteCommandHandler.expirePublication(android.os.SystemClock.elapsedRealtimeNanos())) {
                    stop()
                } else if (FakeLoc.enable && FakeLoc.isSystemServerProcess && !FakeLoc.externallyDriven) {
                    LocationServiceHook.callOnLocationChanged()
                }
            }.onFailure { Logger.error("Location publisher failed", it) }
        }, interval, interval, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        future?.cancel(false)
        future = null
        executor?.shutdownNow()
        executor = null
    }
}
