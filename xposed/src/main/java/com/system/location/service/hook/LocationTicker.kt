package com.system.location.service.hook

import com.system.location.service.core.runtime.LatestStateWorker
import com.system.location.service.core.runtime.RuntimeSnapshotObservation
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.runtime.DeliveryWorker
import com.system.location.service.hook.runtime.GenerationDelivery
import com.system.location.service.hook.runtime.HookRuntime
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

    // A slow listener may delay its delivery, but must not hold up the next sample or lease expiry.
    private val publishedLocations = LatestStateWorker<RuntimeSnapshotObservation>(
        deliver = { publication ->
            GenerationDelivery(HookRuntime.executionGate).run {
                if (FakeLoc.externallyDriven) {
                    try { LocationServiceHook.callOnLocationChanged(publication) }
                    catch (error: Exception) { Logger.error("Published location delivery failed", error) }
                }
            }
        },
        onFailure = { Logger.error("Location delivery worker stopped", it) },
        threadName = "PortalLocationDelivery",
    )

    fun deliverPublishedSample() { publishedLocations.offer(FakeLoc.runtimeSnapshots.observation()) }

    fun retirePublishedDelivery() = publishedLocations.close()

    fun start() = worker.start()

    fun stop(): Boolean = worker.stop()
}
