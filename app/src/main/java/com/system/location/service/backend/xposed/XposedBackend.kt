package com.system.location.service.backend.xposed

import android.content.Context
import android.location.LocationManager
import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.service.MockServiceHelper

/** Migration adapter: the scenario runtime owns movement, the existing IPC only publishes it. */
class XposedBackend(private val context: Context) : LocationBackend {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var started = false
    private var connected = false
    override val type = BackendType.XPOSED
    override val capabilities get() = Capability.entries.associateWith { capability ->
        when (capability) {
            Capability.XPOSED, Capability.GNSS_INJECTION -> CapabilityStatus(
                if (MockServiceHelper.isServiceInit()) Availability.AVAILABLE else Availability.REQUIRES_ACTION,
                "需要在框架中启用 system_server 作用域；Hook 命中仍取决于设备")
            Capability.BACKGROUND_PLAYBACK -> CapabilityStatus(Availability.AVAILABLE, "由定位前台服务运行")
            else -> CapabilityStatus(Availability.UNAVAILABLE, "当前后端未提供此能力")
        }
    }
    override suspend fun prepare(): BackendResult {
        MockServiceHelper.tryInitService(manager)
        connected = MockServiceHelper.isServiceInit()
        return checked(connected, "CONNECT", "启用 Xposed 模块的系统作用域并重启设备")
    }
    override suspend fun start() = BackendResult.Success
    override suspend fun publish(sample: LocationSample): BackendResult {
        if (!started) {
            started = MockServiceHelper.tryOpenMock(manager, sample.speed.toDouble(), sample.altitude,
                sample.accuracy, sample.coordinate.latitude to sample.coordinate.longitude)
            if (!started) return checked(false, "START", "检查系统 Hook 与远程通道")
        }
        return checked(MockServiceHelper.setAltitude(manager, sample.altitude) &&
            MockServiceHelper.setSpeed(manager, sample.speed) &&
            MockServiceHelper.setBearing(manager, sample.bearing.toDouble()) &&
            MockServiceHelper.setLocation(manager, sample.coordinate.latitude, sample.coordinate.longitude),
            "PUBLISH", "检查模块是否仍启用")
    }
    override suspend fun stop(): BackendResult {
        if (!connected) {
            MockServiceHelper.tryInitService(manager)
            connected = MockServiceHelper.isServiceInit()
        }
        if (!connected) return checked(false, "STOP", "重新连接系统服务后重试停止")
        val stopped = MockServiceHelper.tryCloseMock(manager)
        if (stopped) started = false
        return checked(stopped, "STOP", "重新连接系统服务后重试停止")
    }
    override suspend fun release() = stop()
    private fun checked(ok: Boolean, stage: String, suggestion: String) =
        if (ok) BackendResult.Success else BackendResult.Failure(stage, "Xposed 远程命令失败", suggestion)
}
