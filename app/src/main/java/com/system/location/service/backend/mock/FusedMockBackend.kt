package com.system.location.service.backend.mock

import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import kotlinx.coroutines.CancellationException

interface FusedMockPort {
    val capability: CapabilityStatus
    fun diagnostics(): List<BackendDiagnostic>
    suspend fun prepare()
    suspend fun start()
    suspend fun publish(sample: LocationSample)
    suspend fun stop()
}

/** The optional GMS channel may degrade; mandatory providers and cleanup remain authoritative. */
class FusedMockBackend(private val base: LocationBackend, private val fused: FusedMockPort) : LocationBackend {
    private var useFused = false
    private var failure: String? = null
    override val type get() = base.type
    override val capabilities get() = base.capabilities + (Capability.GMS_FUSED to fused.capability)
    override suspend fun diagnose() = base.diagnose() + fused.diagnostics() + listOfNotNull(failure?.let { BackendDiagnostic("GMS_DEGRADED", "UNAVAILABLE", it) })
    override suspend fun prepare(): BackendResult {
        val result = base.prepare()
        if (result is BackendResult.Failure) return result
        failure = null
        return try { fused.prepare(); BackendResult.Success }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { BackendResult.Failure("GMS_CLEANUP", error.message ?: "GMS 残留清理失败", "恢复 Google Play 服务及模拟位置授权后重试停止") }
    }
    override suspend fun start(): BackendResult {
        val result = base.start()
        if (result is BackendResult.Failure) return result
        useFused = false
        try { fused.start(); useFused = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { degrade(error, "GMS 通道启动失败，继续使用标准 Provider") }
        return BackendResult.Success
    }
    override suspend fun publish(sample: LocationSample): BackendResult {
        val result = base.publish(sample)
        if (result is BackendResult.Failure) return result
        if (useFused) try { fused.publish(sample) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { degrade(error, "GMS 提交失败，继续使用标准 Provider") }
        return BackendResult.Success
    }
    private suspend fun degrade(error: Exception, fallback: String) {
        useFused = false
        failure = error.message ?: fallback
        // A stale fused mock fix can mask healthy platform providers: disable it on degradation.
        try { fused.stop() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cleanup: Exception) { failure += "; GMS 清理待重试：${cleanup.message}" }
    }
    override suspend fun pause() = base.pause()
    override suspend fun resume() = base.resume()
    override suspend fun stop(): BackendResult {
        useFused = false
        val result = base.stop()
        return try { fused.stop(); result }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { BackendResult.Failure("GMS_CLEANUP", error.message ?: "GMS 清理未完成", "恢复 Google Play 服务及模拟位置授权后重试停止") }
    }
    override suspend fun release() = stop()
}
