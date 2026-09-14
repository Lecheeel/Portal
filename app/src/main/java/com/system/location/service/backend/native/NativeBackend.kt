package com.system.location.service.backend.native

import android.content.Context
import android.location.LocationManager
import com.system.location.service.backend.xposed.XposedBackend
import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.service.MockServiceHelper

/** Optional sensor extension around Xposed; it is not an independent location publisher. */
class NativeBackend(private val context: Context) : LocationBackend {
    private val xposed = XposedBackend(context)
    private val manager = context.getSystemService(LocationManager::class.java)
    private var sensorsEnabled = false
    override val type = BackendType.NATIVE
    override val capabilities get() = xposed.capabilities + mapOf(
        Capability.NATIVE to CapabilityStatus(Availability.EXPERIMENTAL, "需要 Root 与 Xposed；固定符号、偏移，未进行设备兼容性验证"),
        Capability.SENSOR_SIMULATION to CapabilityStatus(Availability.EXPERIMENTAL, "停止只禁用行为；已安装的 Native Hook 需重启系统才能卸载"))
    override suspend fun prepare(): BackendResult {
        if (!context.getSharedPreferences("scenario_runtime", Context.MODE_PRIVATE).getBoolean("native_opt_in", false))
            return BackendResult.Failure("NATIVE_OPT_IN", "尚未启用实验性 Native 后端", "在后端页面阅读限制并明确选择")
        val connected = xposed.prepare()
        if (connected is BackendResult.Failure) return connected
        val installed = NativeLibraryInstaller.install(context)
        if (installed is BackendResult.Failure) return installed
        val loaded = MockServiceHelper.loadLibrary(manager, NativeLibraryInstaller.DESTINATION)
        if (loaded != "success") return BackendResult.Failure("NATIVE_LOAD", loaded ?: "系统进程拒绝加载", "检查 Root/SELinux 和 ABI，或改用 Xposed 后端")
        if (!MockServiceHelper.prepareNative(manager)) return BackendResult.Failure("NATIVE_SYMBOLS",
            "当前 ROM 缺少所需符号，或 Hook 安装失败", "改用 Xposed 后端；Native 不保证兼容当前系统")
        return BackendResult.Success
    }
    override suspend fun start() = xposed.start()
    override suspend fun publish(sample: LocationSample): BackendResult {
        val published = xposed.publish(sample)
        if (published is BackendResult.Failure) return published
        if (!sensorsEnabled) {
            sensorsEnabled = MockServiceHelper.setNativeEnabled(manager, true)
            if (!sensorsEnabled) return BackendResult.Failure("NATIVE_ENABLE", "无法启用 Native 实验行为", "停止后重试或改用 Xposed")
        }
        return BackendResult.Success
    }
    override suspend fun pause() = xposed.pause()
    override suspend fun resume() = xposed.resume()
    override suspend fun stop(): BackendResult {
        val stopped = xposed.stop() // stop also disables native in system_server.
        if (stopped is BackendResult.Success) sensorsEnabled = false
        return stopped
    }
    override suspend fun release() = stop()
}
