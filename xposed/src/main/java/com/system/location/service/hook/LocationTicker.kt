package com.system.location.service.hook

import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One system-side publisher; delivery continues when the UI is backgrounded. */
object LocationTicker {
    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "LocationPublisher").apply { isDaemon = true }
        }
    }
    private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (!FakeLoc.isSystemServerProcess) return
        future?.cancel(false)
        val interval = FakeLoc.reportIntervalMs.coerceIn(50, 1000)
        future = executor.scheduleWithFixedDelay({
            runCatching {
                if (FakeLoc.enable) LocationServiceHook.callOnLocationChanged()
            }.onFailure { Logger.error("Location publisher failed", it) }
        }, interval, interval, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        future?.cancel(false)
        future = null
    }
}
