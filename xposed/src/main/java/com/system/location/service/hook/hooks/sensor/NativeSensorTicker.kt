package com.system.location.service.hook.hooks.sensor

import android.os.SystemClock
import com.system.location.service.core.runtime.SampleStore
import com.system.location.service.core.sensors.SensorPlayback
import com.system.location.service.core.sensors.SensorPlaybackState
import com.system.location.service.core.sensors.SensorTuning
import com.system.location.service.hook.NativeCommand
import com.system.location.service.hook.runtime.DeliveryWorker
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger

/** Native gets a sensor-rate timeline instead of holding each low-rate location frame. */
internal object NativeSensorTicker {
    private val playback = SensorPlayback()
    private val worker = DeliveryWorker("NativeSensorMotion", {
        if (NativeCommand.motionSensorsEnabled || FakeLoc.runtimeSnapshots.current().configuration.stepSimulation.usesNative)
            20L else 100L
    }, tick = {
        val generation = NativeCommand.sensorGeneration
        val now = SystemClock.elapsedRealtimeNanos()
        val observation = FakeLoc.runtimeSnapshots.observation()
        val state = observation.state
        val fix = state.sample
        val steps = state.configuration.stepSimulation
        val grant = if (NativeCommand.isEnabled && state.configuration.enabled && fix != null &&
            now >= fix.elapsedNanos && now - fix.elapsedNanos < SampleStore.DEFAULT_MAX_AGE_NANOS
        ) SensorPlaybackState(
            "$generation:${observation.simulationGeneration}", fix.elapsedNanos,
            fix.elapsedNanos + SampleStore.DEFAULT_MAX_AGE_NANOS,
            fix.speed, fix.bearing, fix.altitude.coerceIn(-500.0, 20_000.0),
            SensorTuning(state.configuration.sensorCadenceScale, state.configuration.sensorVariation,
                stepsEnabled = steps.usesNative, fixedCadence = steps.fixedCadence.takeIf { steps.enabled }),
        ) else null
        playback.update(grant, now)
        NativeCommand.applySensorTick(generation, playback.frame(now), now, stepsEnabled = steps.usesNative)
    }, failure = {
        NativeCommand.applySensorTick(NativeCommand.sensorGeneration, null, SystemClock.elapsedRealtimeNanos())
        Logger.error("Native sensor timeline failed", it)
    })

    fun start() = worker.start()
    fun stop() { worker.stop(0) }
}
