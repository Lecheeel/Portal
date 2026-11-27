package com.system.location.service.backend.native

import android.content.Context
import android.location.LocationManager
import com.system.location.service.backend.xposed.XposedBackend
import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.service.MockServiceHelper

/** Optional sensor extension around Xposed; it is not an independent location publisher. */
class NativeBackend(private val context: Context) : LocationBackend {
    private val xposed = XposedBackend(context, manageNativeSteps = false)
    private val manager = context.getSystemService(LocationManager::class.java)
    private var sensorsEnabled = false
    override val type = BackendType.NATIVE
    override suspend fun diagnose() = xposed.diagnose() + BackendDiagnostic("NATIVE", "EXPERIMENTAL",
        "Native 依赖 Xposed；当前实例传感器启用状态：$sensorsEnabled", "符号安装成功不等于 ROM 行为已验证")
    override val capabilities get() = xposed.capabilities + mapOf(
        Capability.NATIVE to CapabilityStatus(Availability.EXPERIMENTAL, "需要 Root 与 Xposed；固定符号、偏移，未进行设备兼容性验证"),
        Capability.SENSOR_SIMULATION to CapabilityStatus(Availability.EXPERIMENTAL, "停止只禁用行为；已安装的 Native Hook 需重启系统才能卸载"))
    override suspend fun prepare(): BackendResult {
        if (!context.getSharedPreferences("scenario_runtime", Context.MODE_PRIVATE).getBoolean("native_opt_in", false))
            return BackendResult.Failure("NATIVE_OPT_IN", "尚未启用实验性 Native 后端", "在后端页面阅读限制并明确选择")
        val connected = xposed.prepare()
        if (connected is BackendResult.Failure) return connected
        return NativeSensorSession.prepare(context, manager)
    }
    override suspend fun start(): BackendResult {
        val started = xposed.start()
        if (started is BackendResult.Failure) return started
        return NativeSensorSession.enable(manager, motionSensors = true).also {
            sensorsEnabled = it is BackendResult.Success
        }
    }
    override suspend fun publish(sample: LocationSample, scenarioId: String?): BackendResult {
        val published = xposed.publish(sample, scenarioId)
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
