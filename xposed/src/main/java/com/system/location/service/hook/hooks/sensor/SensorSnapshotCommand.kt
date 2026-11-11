package com.system.location.service.hook.hooks.sensor

import android.os.Bundle
import android.os.SystemClock
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingTable
import com.system.location.service.core.runtime.SampleStore
import com.system.location.service.core.sensors.SensorPlaybackState
import com.system.location.service.core.sensors.SensorTargets
import com.system.location.service.core.sensors.SensorTuning
import com.system.location.service.hook.NativeCommand
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc

/** Read-only IPC for an explicitly selected application UID, independent of controller auth. */
object SensorSnapshotCommand {
    const val COMMAND = "get_sensor_state"

    fun read(uid: Int, output: Bundle): Boolean {
        output.clear()
        if (!FakeLoc.isSystemServerProcess) return false
        val observation = FakeLoc.runtimeSnapshots.observation()
        val state = observation.state
        val packages = BinderUtils.getUidPackageNames(uid = uid)?.toSet().orEmpty()
        if (!SensorTargets.allows(uid, packages, state.configuration.sensorTargets)) return false
        if (!state.configuration.enabled || NativeCommand.isEnabled) return true
        val route = RoutingTable(state.routing ?: com.system.location.service.core.routing.RoutingConfig())
            .decision(uid, packages, false)
        if (route == RoutingDecision.Deny) return true
        val now = SystemClock.elapsedRealtimeNanos()
        val fix = when (route) {
            is RoutingDecision.Scenario -> FakeLoc.scenarioSamples.latest(route.scenarioId, now)?.sample ?: state.sample
            else -> state.sample
        } ?: return true
        if (now < fix.elapsedNanos || now - fix.elapsedNanos >= SampleStore.DEFAULT_MAX_AGE_NANOS) return true
        val motion = SensorPlaybackState(
            "${FakeLoc.runtimeSnapshots.instanceId}:${observation.simulationGeneration}:${route.hashCode()}",
            fix.elapsedNanos,
            minOf(now + 1_000_000_000, fix.elapsedNanos + SampleStore.DEFAULT_MAX_AGE_NANOS),
            if (route is RoutingDecision.Point) 0f else fix.speed,
            fix.bearing, fix.altitude.coerceIn(-500.0, 20_000.0),
            SensorTuning(state.configuration.sensorCadenceScale, state.configuration.sensorVariation),
        )
        SensorStateWire.write(output, motion)
        return true
    }
}

internal object SensorStateWire {
    fun write(bundle: Bundle, state: SensorPlaybackState) = with(bundle) {
        putInt("sensor_version", 1)
        putString("session", state.session)
        putLong("sample_nanos", state.sampleNanos)
        putLong("expires_nanos", state.expiresNanos)
        putFloat("speed", state.speedMps)
        putFloat("bearing", state.bearingDeg)
        putDouble("altitude", state.altitudeMeters)
        putDouble("cadence_scale", state.tuning.cadenceScale)
        putDouble("variation", state.tuning.variability)
    }

    fun read(bundle: Bundle): SensorPlaybackState? = runCatching {
        if (bundle.getInt("sensor_version") != 1) return null
        SensorPlaybackState(bundle.getString("session") ?: return null,
            bundle.getLong("sample_nanos", -1), bundle.getLong("expires_nanos", -1),
            bundle.getFloat("speed", Float.NaN), bundle.getFloat("bearing", Float.NaN),
            bundle.getDouble("altitude", Double.NaN),
            SensorTuning(bundle.getDouble("cadence_scale", Double.NaN), bundle.getDouble("variation", Double.NaN)))
    }.getOrNull()
}
