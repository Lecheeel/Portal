package com.system.location.service.hook

import android.os.Bundle
import android.os.SystemClock
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.performance.PerformanceProbe
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.security.PublicationLease
import com.system.location.service.hook.security.SampleWire
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

    fun publish(fix: LocationSample, rely: Bundle): Boolean {
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            publishInternal(fix, rely)
        } finally {
            publishProbe.record((SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(0L))
        }
    }

    private fun publishInternal(fix: LocationSample, rely: Bundle): Boolean {
        if (!lease.renew(fix.elapsedNanos, SystemClock.elapsedRealtimeNanos())) return false
        val needsTicker = !FakeLoc.enable || !FakeLoc.externallyDriven
        FakeLoc.acceptSample(fix)
        rely.getString("scenario_id")?.takeIf { it.isNotBlank() }?.let { FakeLoc.acceptScenarioSample(it, fix) }
        if (FakeLoc.isSystemServerProcess) {
            val mode = when {
                FakeLoc.speed < 0.3 -> 0
                FakeLoc.speed < 2.5 -> 1
                FakeLoc.speed < 7.0 -> 2
                else -> 3
            }
            NativeCommand.setMotionState(mode, FakeLoc.speed.toFloat(), FakeLoc.bearing.toFloat(), FakeLoc.altitude.toFloat())
        }
        FakeLoc.enable = true
        lastPublishMillis = fix.timeMillis
        if (needsTicker) LocationTicker.start()
        if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
        return true
    }

    fun expire(nowNanos: Long): Boolean {
        if (!FakeLoc.externallyDriven || !lease.expired(nowNanos)) return false
        FakeLoc.enable = false
        FakeLoc.hasBearings = false
        lease.clear()
        NativeCommand.disable()
        return true
    }

    fun restore(rely: Bundle) {
        val fix = SampleWire.read(rely) ?: return
        if (!lease.renew(fix.elapsedNanos, SystemClock.elapsedRealtimeNanos())) {
            FakeLoc.enable = false
            return
        }
        FakeLoc.acceptSample(fix)
        LocationTicker.start()
    }
}
