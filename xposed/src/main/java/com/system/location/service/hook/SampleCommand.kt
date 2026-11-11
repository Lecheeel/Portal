package com.system.location.service.hook

import android.os.Bundle
import android.os.SystemClock
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.performance.PerformanceProbe
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.security.PublicationLease
import com.system.location.service.core.runtime.ScenarioSampleStore
import com.system.location.service.hook.utils.FakeLoc

/** P5-08: publication lease/sample command extracted from RemoteCommandHandler. */
object SampleCommand {
    private val lease = PublicationLease()
    private val publishProbe = PerformanceProbe()

    @Volatile
    var lastPublishMillis = 0L
        private set

    fun publishStats() = publishProbe.snapshot()

    fun accepts(fix: LocationSample): Boolean =
        lease.accepts(fix.elapsedNanos, SystemClock.elapsedRealtimeNanos())

    fun clearLease() {
        lease.clear()
    }

    /** Used only by ordered full-state replication; this does not publish or start native injection. */
    fun restoreLease(fix: LocationSample?, nowNanos: Long): Boolean {
        if (fix != null && !lease.restore(fix.elapsedNanos, nowNanos)) return false
        if (fix == null) lease.clear()
        lastPublishMillis = fix?.timeMillis ?: 0L
        return true
    }

    fun publish(fix: LocationSample, rely: Bundle): Boolean {
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            publishInternal(fix, rely)
        } finally {
            publishProbe.record((SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(0L))
        }
    }

    internal fun scenarioId(value: Any?): String? {
        if (value == null) return null
        require(value is String && value.isNotBlank() && value.length <= ScenarioSampleStore.MAX_ID_LENGTH)
        return value
    }

    @Suppress("DEPRECATION")
    private fun publishInternal(fix: LocationSample, rely: Bundle): Boolean {
        val scenario = scenarioId(rely.get("scenario_id"))
        if (!lease.renew(fix.elapsedNanos, SystemClock.elapsedRealtimeNanos())) return false
        val needsTicker = !FakeLoc.enable || !FakeLoc.externallyDriven
        FakeLoc.acceptSample(fix, scenario)
        lastPublishMillis = fix.timeMillis
        if (needsTicker) LocationTicker.start()
        if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
        return true
    }

    fun expire(nowNanos: Long): Boolean {
        if (!FakeLoc.externallyDriven || !lease.expired(nowNanos)) return false
        FakeLoc.stopRuntime()
        lease.clear()
        NativeCommand.disable()
        return true
    }

}
