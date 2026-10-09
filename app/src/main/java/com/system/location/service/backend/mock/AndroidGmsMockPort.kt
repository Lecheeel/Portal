package com.system.location.service.backend.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.ext.gmsMockEnabled
import com.system.location.service.ext.experimentalClearMockFlag
import kotlinx.coroutines.*
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
class AndroidGmsMockPort(context: Context) : FusedMockPort {
    private val context = context.applicationContext
    private val journal = context.getSharedPreferences("gms_mock_ownership", Context.MODE_PRIVATE)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this.context) }
    private val flag = MockFlagExperiment()
    private var enabling: Task<Void>? = null
    private var disabling: Task<Void>? = null
    private var publishing: Task<Void>? = null
    private var active = false
    private var detail = "等待启动"
    private var submitted = 0L
    private var coalesced = 0L
    private var lastSuccessNanos: Long? = null
    private val requested = context.gmsMockEnabled
    private val available get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    override val capability get() = when {
        !requested -> CapabilityStatus(Availability.REQUIRES_ACTION, "可选 GMS 通道已关闭，在设置中启用")
        !available -> CapabilityStatus(Availability.UNAVAILABLE, "此设备没有可用的 Google Play 服务")
        active -> CapabilityStatus(Availability.AVAILABLE, "Google 融合定位 Mock 已启用；不代表目标 App 采用")
        else -> CapabilityStatus(Availability.EXPERIMENTAL, detail)
    }
    override fun diagnostics() = listOf(BackendDiagnostic("GMS_FUSED", when {
        active -> "AVAILABLE"; !requested -> "DISABLED"; !available -> "UNAVAILABLE"; else -> "EXPERIMENTAL"
    }, "$detail；已请求=$submitted；合并跳过=$coalesced；最近成功单调时间=${lastSuccessNanos?.div(1_000_000) ?: 0}ms；待清理=${journal.getBoolean("pending", false)}"))
    private fun record(pending: Boolean) { check(journal.edit().putBoolean("pending", pending).commit()) { "GMS 所有权记录保存失败" } }
    override suspend fun prepare() { if (journal.getBoolean("pending", false)) stop() }
    override suspend fun start() {
        if (!requested) { detail = "用户关闭 GMS 通道"; return }
        if (!available) { detail = "Google Play 服务不可用，使用标准 Provider"; return }
        record(true)
        enabling = client.setMockMode(true)
        try {
            await(enabling!!)
            active = true; detail = "GMS Mock 已启用"
        } catch (error: Exception) {
            active = false; detail = "GMS 启动未完成：${error.message}"
            // Queue disable AFTER enable completion, even if enable exceeds our timeout.
            disabling = enabling!!.continueWithTask(DIRECT) { client.setMockMode(false) }
            throw error
        }
    }
    override suspend fun publish(sample: LocationSample) {
        if (!active) return
        publishing?.let { task ->
            if (!task.isComplete) {
                coalesced++
                if (android.os.SystemClock.elapsedRealtimeNanos() - publishStarted > 2_000_000_000) {
                    active = false; detail = "GMS 提交超时，标准 Provider 继续运行"; throw IllegalStateException(detail)
                }
                return
            }
            if (!task.isSuccessful) { active = false; detail = "GMS 提交失败：${task.exception?.message}"; throw IllegalStateException(detail) }
            lastSuccessNanos = publishStarted
        }
        val location = Location("fused").apply {
            latitude = sample.latitude; longitude = sample.longitude; altitude = sample.altitude
            accuracy = sample.accuracy; speed = sample.speed; bearing = sample.bearing
            time = sample.timeMillis; elapsedRealtimeNanos = sample.elapsedNanos
            verticalAccuracyMeters = sample.accuracy; speedAccuracyMetersPerSecond = 0.1f; bearingAccuracyDegrees = 1f
        }
        if (context.experimentalClearMockFlag) flag.clear(location)
        publishStarted = android.os.SystemClock.elapsedRealtimeNanos()
        publishing = client.setMockLocation(location)
        submitted++
    }
    private var publishStarted = 0L
    override suspend fun stop() {
        active = false
        if (!journal.getBoolean("pending", false)) return
        check(available) { "Google Play 服务不可用，保留待清理记录" }
        val disable = disabling?.takeUnless { it.isComplete && !it.isSuccessful } ?: (publishing ?: enabling)?.continueWithTask(DIRECT) { client.setMockMode(false) }
            ?: client.setMockMode(false)
        disabling = disable
        await(disable)
        record(false)
        enabling = null; publishing = null; disabling = null
        detail = "GMS Mock 已停止"
    }
    private suspend fun await(task: Task<Void>) {
        val complete = withTimeoutOrNull(2000) { while (!task.isComplete) delay(10); true } ?: false
        check(complete) { "GMS 操作超过2秒" }
        check(task.isSuccessful) { task.exception?.message ?: "GMS 操作失败或取消" }
    }
    companion object { private val DIRECT = Executor { it.run() } }
}
