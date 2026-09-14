package com.system.location.service.backend.mock

import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample

/** Android operations are injected so partial registration and cleanup can be tested on the JVM. */
interface MockProviderPort {
    fun permissionGranted(): Boolean
    fun pendingProviders(): Set<String>
    fun register(provider: String)
    fun enable(provider: String, enabled: Boolean)
    fun publish(provider: String, sample: LocationSample)
    fun remove(provider: String)
}

class MockProviderBackend(private val port: MockProviderPort) : LocationBackend {
    enum class Phase { IDLE, PREPARED, RUNNING, PAUSED, STOPPED, ERROR }
    var phase = Phase.IDLE
        private set
    private val owned = linkedSetOf<String>()
    override val type = BackendType.MOCK_PROVIDER
    override val capabilities get() = Capability.entries.associateWith {
        when (it) {
            Capability.STANDARD_MOCK -> CapabilityStatus(
                if (runCatching { port.permissionGranted() }.getOrDefault(false)) Availability.AVAILABLE else Availability.REQUIRES_ACTION,
                "仅 Android 标准模拟位置；请在开发者选项中选择本应用为模拟位置信息应用")
            Capability.BACKGROUND_PLAYBACK -> CapabilityStatus(Availability.AVAILABLE, "定位前台服务；受系统电源策略影响")
            Capability.GMS_FUSED -> CapabilityStatus(Availability.UNAVAILABLE, "未集成 GMS mock API，不能保证 Google 融合定位采纳标准 Provider")
            else -> CapabilityStatus(Availability.UNAVAILABLE, "标准 Mock Provider 不提供 Hook 能力")
        }
    }

    override suspend fun prepare(): BackendResult {
        if (phase in setOf(Phase.RUNNING, Phase.PAUSED)) return failure("PREPARE", "请先停止运行中的场景")
        return guarded("PREPARE") {
            if (!port.permissionGranted()) return@guarded failure("CHECK_MOCK_PERMISSION", "未获得标准模拟位置授权")
            owned += port.pendingProviders()
            if (owned.isNotEmpty()) {
                val cleanup = cleanup()
                if (cleanup is BackendResult.Failure) return@guarded cleanup
            }
            phase = Phase.PREPARED
            BackendResult.Success
        }
    }

    override suspend fun start(): BackendResult {
        if (phase != Phase.PREPARED) return failure("START_PROVIDER", "后端尚未准备完成")
        return guarded("START_PROVIDER") {
            for (provider in listOf("gps", "network")) {
                // Track attempted registration too: a platform operation may fail after allocating.
                owned += provider
                port.register(provider)
                port.enable(provider, true)
            }
            phase = Phase.RUNNING
            BackendResult.Success
        }
    }

    override suspend fun publish(sample: LocationSample): BackendResult {
        if (phase !in setOf(Phase.RUNNING, Phase.PAUSED)) return failure("PUBLISH", "Provider 未启动")
        return guarded("PUBLISH") {
            val fix = if (phase == Phase.PAUSED) sample.copy(speed = 0f) else sample
            owned.forEach { port.publish(it, fix) }
            BackendResult.Success
        }
    }
    override suspend fun pause(): BackendResult {
        if (phase != Phase.RUNNING) return failure("PAUSE", "场景不在运行状态")
        phase = Phase.PAUSED
        return BackendResult.Success
    }
    override suspend fun resume(): BackendResult {
        if (phase != Phase.PAUSED) return failure("RESUME", "场景尚未暂停")
        phase = Phase.RUNNING
        return BackendResult.Success
    }
    override suspend fun stop(): BackendResult = cleanup()
    override suspend fun release(): BackendResult = cleanup()

    private inline fun guarded(stage: String, action: () -> BackendResult): BackendResult = try { action() }
    catch (error: Exception) {
        val cleanup = cleanup()
        phase = Phase.ERROR
        failure(stage, (error.message ?: error.javaClass.simpleName) +
            if (cleanup is BackendResult.Failure) "; 清理未完成: ${cleanup.reason}" else "")
    }
    private fun cleanup(): BackendResult {
        val errors = mutableListOf<String>()
        owned.toList().asReversed().forEach { provider ->
            // Removal restores the original provider even when disabling it fails.
            runCatching { port.enable(provider, false) }
            try { port.remove(provider); owned.remove(provider) }
            catch (error: Exception) { errors += "$provider: ${error.message ?: error.javaClass.simpleName}" }
        }
        phase = if (errors.isEmpty()) Phase.STOPPED else Phase.ERROR
        return if (errors.isEmpty()) BackendResult.Success else failure("CLEANUP_PROVIDER", errors.joinToString("; "))
    }
    private fun failure(stage: String, reason: String) = BackendResult.Failure(stage, reason,
        "开发者选项 → 选择模拟位置信息应用 → LocationService；恢复授权后重试停止/启动")
}
