package com.system.location.service.backend.xposed

import android.content.Context
import android.location.LocationManager
import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.service.MockServiceHelper

/** The scenario runtime owns movement. One authenticated IPC sends each immutable fix. */
class XposedBackend(private val context: Context) : LocationBackend {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var connected = false
    override val type = BackendType.XPOSED
    override val capabilities get() = Capability.entries.associateWith { capability ->
        when (capability) {
            Capability.XPOSED, Capability.GNSS_INJECTION -> CapabilityStatus(
                if (connected) Availability.AVAILABLE else Availability.REQUIRES_ACTION,
                "需要在框架中启用 system_server 作用域；Hook 命中仍取决于设备")
            Capability.BACKGROUND_PLAYBACK -> CapabilityStatus(Availability.AVAILABLE, "由定位前台服务运行")
            else -> CapabilityStatus(Availability.UNAVAILABLE, "当前后端未提供此能力")
        }
    }
    override suspend fun prepare(): BackendResult {
        MockServiceHelper.tryInitService(manager)
        connected = MockServiceHelper.isServiceInit()
        if (!connected) return checked(false, "CONNECT", "启用 Xposed 模块的系统作用域并重启设备")
        val status = MockServiceHelper.runtimeStatus(manager)
        if (status?.getInt("sample_version") != 1) {
            connected = false
            return BackendResult.Failure("PROTOCOL", "已加载的系统模块不支持统一定位快照", "安装当前版本并重启设备")
        }
        return checked(MockServiceHelper.putConfig(manager, context), "CONFIGURE", "检查系统服务连接")
    }
    override suspend fun start() = BackendResult.Success
    override suspend fun publish(sample: LocationSample): BackendResult {
        return checked(MockServiceHelper.publishSample(manager, sample),
            "PUBLISH", "检查模块是否仍启用")
    }
    override suspend fun stop(): BackendResult {
        if (!connected) {
            MockServiceHelper.tryInitService(manager)
            connected = MockServiceHelper.isServiceInit()
        }
        if (!connected) return checked(false, "STOP", "重新连接系统服务后重试停止")
        val stopped = MockServiceHelper.tryCloseMock(manager)
        return checked(stopped, "STOP", "重新连接系统服务后重试停止")
    }
    override suspend fun release() = stop()
    private fun checked(ok: Boolean, stage: String, suggestion: String) =
        if (ok) BackendResult.Success else BackendResult.Failure(stage, "Xposed 远程命令失败", suggestion)
}
