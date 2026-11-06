package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.jni.Dobby

/** P5-08: native library lifecycle extracted from RemoteCommandHandler. */
object NativeCommand {
    @Volatile
    var isLoaded = false
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
        return Dobby.prepareSensors()
    }

    fun setEnabled(rely: Bundle): Boolean {
        if (!FakeLoc.isSystemServerProcess) return false
        if (!isLoaded) return !rely.getBoolean("enabled")
        if (rely.getBoolean("enabled") && (!FakeLoc.enable || !FakeLoc.externallyDriven)) return false
        Dobby.setStatus(rely.getBoolean("enabled"))
        return true
    }

    fun setMotionState(mode: Int, speed: Float, bearing: Float, altitude: Float) {
        if (isLoaded) Dobby.setMotionState(mode, speed, bearing, altitude)
    }

    fun disable() {
        if (isLoaded) Dobby.setStatus(false)
    }
}
