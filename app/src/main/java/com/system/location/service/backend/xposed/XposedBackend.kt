package com.system.location.service.backend.xposed

import android.content.Context
import android.location.LocationManager
import android.os.Debug
import android.os.SystemClock
import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.performance.PerformanceProbe
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.service.MockServiceHelper

/** The scenario runtime owns movement. One authenticated IPC sends each immutable fix. */
class XposedBackend(private val context: Context) : LocationBackend {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val publishProbe = PerformanceProbe()
    private var previousCpuMillis = 0L
    private var previousCpuWallMillis = 0L
    private var connected = false
    override val type = BackendType.XPOSED
    override suspend fun diagnose(): List<BackendDiagnostic> {
        if (!MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(manager)
        val status = MockServiceHelper.runtimeStatus(manager)
        connected = status?.getInt("sample_version")?.let(SampleWire::isCompatible) == true
        if (!connected) return listOf(BackendDiagnostic("REMOTE_COMMAND", "FAILED", "未连接兼容的系统模块", "启用系统作用域，安装当前版本并重启"))
        val scope = status!!.getString("hook_scope") ?: "unknown"
        val expected = status.getInt("hook_expected")
        val missing = status.getInt("hook_missing")
        val missingPoints = status.getStringArrayList("hook_missing_points").orEmpty()
        val publishSamples = status.getInt("publish_samples", 0)
        val latency = if (publishSamples > 0) {
            val p50 = status.getLong("publish_p50_nanos") / 1_000_000.0
            val p95 = status.getLong("publish_p95_nanos") / 1_000_000.0
            val max = status.getLong("publish_max_nanos") / 1_000_000.0
            BackendDiagnostic(
                "PUBLISH_LATENCY",
                if (p95 > 100.0) "WARN" else "INFO",
                "n=$publishSamples · p50=%.1f ms · p95=%.1f ms · max=%.1f ms".format(p50, p95, max),
            )
        } else null
        val appLatency = publishProbe.snapshot()?.let { stats ->
            BackendDiagnostic(
                "APP_PUBLISH_LATENCY",
                if (stats.p95Nanos > 100_000_000L) "WARN" else "INFO",
                "n=${stats.samples} · p50=%.1f ms · p95=%.1f ms · max=%.1f ms".format(
                    stats.p50Nanos / 1_000_000.0,
                    stats.p95Nanos / 1_000_000.0,
                    stats.maxNanos / 1_000_000.0,
                ),
            )
        }
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val heapMaxMb = runtime.maxMemory() / (1024 * 1024)
        val appMemory = BackendDiagnostic(
            "APP_MEMORY",
            "INFO",
            "pss=${memory.totalPss / 1024} MB · heap=$heapUsedMb/$heapMaxMb MB",
        )
        val cpuMillis = android.os.Process.getElapsedCpuTime()
        val wallMillis = SystemClock.elapsedRealtime()
        val cpuPercent = if (previousCpuWallMillis > 0L && wallMillis > previousCpuWallMillis) {
            (cpuMillis - previousCpuMillis).coerceAtLeast(0L) * 100.0 / (wallMillis - previousCpuWallMillis)
        } else 0.0
        previousCpuMillis = cpuMillis
        previousCpuWallMillis = wallMillis
        val appCpu = BackendDiagnostic("APP_CPU", "INFO", "cpu=%.1f%% (since last diagnose)".format(cpuPercent))
        return listOf(BackendDiagnostic("REMOTE_COMMAND", "CONNECTED", "system_server 远程通道已连接"),
            BackendDiagnostic("HOOK_SUMMARY", "INFO", "system_server：installed=${status.getInt("hook_installed")}, matched=${status.getInt("hook_matched")}, skipped=${status.getInt("hook_skipped")}, failed=${status.getInt("hook_failed")}"),
            BackendDiagnostic("HOOK_OWNERSHIP", if (missing == 0) "INFO" else "WARN",
                "scope=$scope · expected=$expected · missing=$missing" +
                    if (missingPoints.isEmpty()) "" else " · ${missingPoints.joinToString(", ")}"),
            BackendDiagnostic("LAST_PUBLISH", "INFO", "系统侧最近发布时间：${status.getLong("last_publish")}; 启用状态：${status.getBoolean("is_start")}")) +
            listOfNotNull(latency) +
            listOfNotNull(appLatency) +
            listOf(appMemory) +
            listOf(appCpu) +
            status.getStringArrayList("hook_details").orEmpty().take(60).map { BackendDiagnostic("HOOK_POINT", "INFO", it) }
    }
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
        if (status?.getInt("sample_version")?.let(SampleWire::isCompatible) != true) {
            connected = false
            return BackendResult.Failure("PROTOCOL", "已加载的系统模块不支持统一定位快照", "安装当前版本并重启设备")
        }
        return checked(MockServiceHelper.putConfig(manager, context), "CONFIGURE", "检查系统服务连接")
    }
    override suspend fun start() = BackendResult.Success
    override suspend fun publish(sample: LocationSample, scenarioId: String?): BackendResult {
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            checked(MockServiceHelper.publishSample(manager, sample, scenarioId),
                "PUBLISH", "检查模块是否仍启用")
        } finally {
            publishProbe.record((SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(0L))
        }
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
