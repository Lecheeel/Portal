package com.system.location.service.backend.mock

import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import kotlinx.coroutines.*

/** Task seam: production wraps the GMS Task; tests control completion without Android. */
internal interface GmsOperation {
    val isComplete: Boolean
    val isSuccessful: Boolean
    val failure: Exception?
    fun then(action: () -> GmsOperation?): GmsOperation
}
internal interface GmsOwnership {
    val pending: Boolean
    fun record(pending: Boolean)
}

/** Owns the actual GMS lifecycle, including submission coalescing and cleanup ordering. */
internal class GmsMockSession(
    private val requested: Boolean,
    private val available: () -> Boolean,
    private val ownership: GmsOwnership,
    private val setMode: (Boolean) -> GmsOperation,
    private val submit: (LocationSample) -> GmsOperation,
    private val nanos: () -> Long,
    private val timeoutMs: Long = 2000,
) : FusedMockPort {
    private val generationLock = Any()
    private var generation = 0L
    // Timeouts only end our wait; the remote operation may still change mock mode later.
    // Retain enable, disable, and chained compensation Tasks until every one is terminal.
    private val modeOperations = mutableListOf<GmsOperation>()
    private var publishing: GmsOperation? = null
    private var active = false
    private var detail = "等待启动"
    private var submitted = 0L
    private var coalesced = 0L
    private var lastSuccessNanos: Long? = null
    private var publishStarted = 0L
    override val capability get() = when {
        !requested -> CapabilityStatus(Availability.REQUIRES_ACTION, "可选 GMS 通道已关闭，在设置中启用")
        !available() -> CapabilityStatus(Availability.UNAVAILABLE, "此设备没有可用的 Google Play 服务")
        active -> CapabilityStatus(Availability.AVAILABLE, "Google 融合定位 Mock 已启用；不代表目标 App 采用")
        else -> CapabilityStatus(Availability.EXPERIMENTAL, detail)
    }
    override fun diagnostics() = listOf(BackendDiagnostic("GMS_FUSED", when {
        active -> "AVAILABLE"; !requested -> "DISABLED"; !available() -> "UNAVAILABLE"; else -> "EXPERIMENTAL"
    }, "$detail；已请求=$submitted；合并跳过=$coalesced；最近成功单调时间=${lastSuccessNanos?.div(1_000_000) ?: 0}ms；待清理=${ownership.pending}"))
    override suspend fun prepare() { if (ownership.pending) stop() }
    override suspend fun start() {
        if (!requested) { detail = "用户关闭 GMS 通道"; return }
        if (!available()) { detail = "Google Play 服务不可用，使用标准 Provider"; return }
        check(!ownership.pending) { "GMS 清理尚未完成，先重试停止" }
        val token = synchronized(generationLock) { ++generation }
        ownership.record(true)
        val enabling = synchronized(generationLock) { setMode(true).also { modeOperations += it } }
        try {
            await(enabling)
            active = true; detail = "GMS Mock 已启用"
        } catch (error: Exception) {
            active = false; detail = "GMS 启动未完成：${error.message}"
            // Track the continuation too: enable may be complete before its callback runs.
            synchronized(generationLock) {
                modeOperations += enabling.then {
                    synchronized(generationLock) {
                        if (generation == token) setMode(false).also { modeOperations += it } else null
                    }
                }
            }
            throw error
        }
    }
    override suspend fun publish(sample: LocationSample) {
        if (!active) return
        publishing?.let { task ->
            if (!task.isComplete) {
                coalesced++
                if (nanos() - publishStarted > 2_000_000_000) {
                    active = false; detail = "GMS 提交超时，标准 Provider 继续运行"; throw IllegalStateException(detail)
                }
                return
            }
            if (!task.isSuccessful) { active = false; detail = "GMS 提交失败：${task.failure?.message}"; throw IllegalStateException(detail) }
            lastSuccessNanos = publishStarted
        }
        publishStarted = nanos()
        publishing = submit(sample)
        submitted++
    }
    override suspend fun stop() {
        active = false
        if (!ownership.pending) return
        check(available()) { "Google Play 服务不可用，保留待清理记录" }
        // Publication can remain pending forever. Disable is independent of that Task.
        val disable = synchronized(generationLock) {
            // Every retry gets a fresh attempt, without forgetting older remote operations.
            modeOperations.removeAll { it.isComplete }
            setMode(false).also { modeOperations += it }
        }
        await(disable)
        synchronized(generationLock) {
            check(modeOperations.all { it.isComplete }) { "GMS 模式操作仍未完成，保留迟到清理和所有权" }
            ownership.record(false)
            generation++
            modeOperations.clear()
        }
        publishing = null
        detail = "GMS Mock 已停止"
    }
    private suspend fun await(task: GmsOperation) {
        val complete = withTimeoutOrNull(timeoutMs) { while (!task.isComplete) delay(1); true } ?: false
        check(complete) { "GMS 操作超时" }
        check(task.isSuccessful) { task.failure?.message ?: "GMS 操作失败或取消" }
    }
}
