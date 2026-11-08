package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.jni.Dobby

/** P5-08: native library lifecycle extracted from RemoteCommandHandler. */
object NativeCommand {
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

    fun prepare(): Boolean {
        if (!FakeLoc.isSystemServerProcess || !isLoaded) return false
        Dobby.setStatus(false)
        isEnabled = false
        return Dobby.prepareSensors()
    }

    fun setEnabled(enabled: Boolean): Boolean {
        if (!FakeLoc.isSystemServerProcess) return false
        if (!isLoaded) return !enabled
        Dobby.setStatus(enabled)
        isEnabled = enabled
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

    fun disable() {
        if (isLoaded) Dobby.setStatus(false)
        isEnabled = false
    }
}
