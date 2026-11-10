package com.system.location.service.hook

import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.runtime.DeliveryWorker
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger

/** One system-side publisher; delivery continues when the UI is backgrounded. */
object LocationTicker {
    private val worker = DeliveryWorker("LocationPublisher",
        intervalMillis = { FakeLoc.reportIntervalMs.coerceIn(50, 1000) },
        delayFirstTick = true,
        tick = {
            if (RemoteCommandHandler.expirePublication(android.os.SystemClock.elapsedRealtimeNanos())) {
                stop()
            } else if (FakeLoc.enable && FakeLoc.isSystemServerProcess && !FakeLoc.externallyDriven) {
                LocationServiceHook.callOnLocationChanged()
            }
        },
        failure = { Logger.error("Location publisher failed", it) })

    fun start() = worker.start()

    fun stop(): Boolean = worker.stop()
}
