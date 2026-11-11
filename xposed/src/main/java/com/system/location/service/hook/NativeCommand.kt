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

    @Volatile
    var isEnabled = false
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
        Dobby.setStatus(false)
        isEnabled = false
        sensorGeneration++
        com.system.location.service.hook.hooks.sensor.NativeSensorTicker.stop()
        return Dobby.prepareSensors()
    }

    @Synchronized fun setEnabled(enabled: Boolean): Boolean {
        if (!FakeLoc.isSystemServerProcess) return false
        if (!isLoaded) return !enabled
        if (enabled == isEnabled) return true
        Dobby.setStatus(false)
        isEnabled = enabled
        sensorGeneration++
        if (enabled) com.system.location.service.hook.hooks.sensor.NativeSensorTicker.start()
        else com.system.location.service.hook.hooks.sensor.NativeSensorTicker.stop()
        return true
    }

    fun setEnabled(rely: Bundle): Boolean = setEnabled(rely.getBoolean("enabled"))

    fun setSensorFrame(
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
        frame: com.system.location.service.core.sensors.SensorFrame?, nowNanos: Long) {
        if (!isLoaded || !isEnabled || generation != sensorGeneration) return
        if (frame != null) setSensorFrame(frame, nowNanos)
        Dobby.setStatus(frame != null)
    }

    @Synchronized fun disable() {
        if (isLoaded) Dobby.setStatus(false)
        isEnabled = false
        sensorGeneration++
        com.system.location.service.hook.hooks.sensor.NativeSensorTicker.stop()
    }
}
