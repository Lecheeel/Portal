package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.jni.Dobby

/** P5-08: native library lifecycle extracted from RemoteCommandHandler. */
object NativeCommand {
    @Volatile internal var sensorGeneration = 0L
        private set
    @Volatile
    var isLoaded = false
        private set

    private var isPrepared = false
    @Volatile
    var isEnabled = false
        private set
    @Volatile
    var motionSensorsEnabled = false
        private set

    fun load(rely: Bundle): Boolean {
        val path = rely.getString("path") ?: return false
        if (!FakeLoc.isSystemServerProcess || path != "/data/local/ext-lib/liblocationext.so") return false
        if (isLoaded && path.endsWith("liblocationext.so")) {
            rely.putString("result", "success")
            return true
        }
        runCatching { System.load(path) }
            .onSuccess {
                rely.putString("result", "success")
                isLoaded = true
            }
            .onFailure { rely.putString("result", it.stackTraceToString()) }
        return true
    }

    @Synchronized fun prepare(): Boolean {
        if (!FakeLoc.isSystemServerProcess || !isLoaded) return false
        if (!isPrepared) {
            isPrepared = runCatching {
                Dobby.setSensorChannels(motion = false, steps = false)
                Dobby.prepareSensors()
            }.getOrDefault(false)
        }
        return isPrepared
    }

    @Synchronized fun setEnabled(enabled: Boolean, motionSensors: Boolean = true): Boolean {
        if (!FakeLoc.isSystemServerProcess) return false
        if (!isPrepared) return !enabled
        val nextMotion = enabled && motionSensors
        if (enabled == isEnabled && nextMotion == motionSensorsEnabled) return true
        Dobby.setStatus(false)
        motionSensorsEnabled = nextMotion
        isEnabled = enabled
        sensorGeneration++
        if (enabled) com.system.location.service.hook.hooks.sensor.NativeSensorTicker.start()
        else com.system.location.service.hook.hooks.sensor.NativeSensorTicker.stop()
        return true
    }

    fun setEnabled(rely: Bundle): Boolean =
        setEnabled(rely.getBoolean("enabled"), rely.getBoolean("motion_sensors", true))

    private fun setSensorFrame(
        frame: com.system.location.service.core.sensors.SensorFrame,
        syncNanos: Long,
    ) {
        if (isLoaded) {
            Dobby.setSensorFrame(
                frame.stepCount,
                frame.stepCadence,
                syncNanos,
                frame.accelerometer,
                frame.gyroscope,
                frame.magnetometer,
                frame.barometerHpa,
            )
        }
    }

    @Synchronized internal fun applySensorTick(generation: Long,
        frame: com.system.location.service.core.sensors.SensorFrame?, nowNanos: Long, stepsEnabled: Boolean = false) {
        if (!isEnabled || generation != sensorGeneration) return
        val active = frame != null && (motionSensorsEnabled || stepsEnabled)
        if (active) {
            setSensorFrame(frame, nowNanos)
            Dobby.setSensorChannels(motionSensorsEnabled, stepsEnabled)
        }
        Dobby.setStatus(active)
    }

    fun disable() { setEnabled(false) }
}
