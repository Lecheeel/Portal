package com.system.location.service.backend.native

import android.content.Context
import android.location.LocationManager
import com.system.location.service.core.backend.BackendResult
import com.system.location.service.service.MockServiceHelper

internal object NativeSensorSession {
    @Synchronized
    fun prepare(context: Context, manager: LocationManager): BackendResult {
        val status = MockServiceHelper.runtimeStatus(manager)
        if (status?.getBoolean("step_simulation_supported") != true) {
            return BackendResult.Failure("NATIVE_MODULE", "当前系统模块不支持步频设置", "启用当前版本的 Xposed 模块并重启设备")
        }
        if (!status.getBoolean("native_loaded")) {
            val installed = NativeLibraryInstaller.install(context)
            if (installed is BackendResult.Failure) return installed
            val loaded = try {
                MockServiceHelper.loadLibrary(manager, NativeLibraryInstaller.DESTINATION)
            } finally {
                NativeLibraryInstaller.removeInstalled()
            }
            if (loaded != "success") return BackendResult.Failure("NATIVE_LOAD",
                loaded ?: "系统进程拒绝加载", "检查 Root、SELinux 和 ABI，或改用 Xposed")
        }
        if (!MockServiceHelper.prepareNative(manager)) return BackendResult.Failure("NATIVE_SYMBOLS",
            "当前 ROM 或已加载的 Native 库不支持计步 Hook", "更新模块后重启设备，或使用 Xposed 步频")
        return BackendResult.Success
    }

    fun enable(manager: LocationManager, motionSensors: Boolean): BackendResult =
        if (MockServiceHelper.setNativeEnabled(manager, true, motionSensors)) BackendResult.Success
        else BackendResult.Failure("NATIVE_ENABLE", "无法启用 Native 传感器", "重新连接系统模块后重试")
}
