package com.system.location.service.core.backend

import com.system.location.service.core.location.LocationSample
import kotlinx.serialization.Serializable

@Serializable enum class BackendType { MOCK_PROVIDER, XPOSED, NATIVE }
enum class Capability { STANDARD_MOCK, XPOSED, NATIVE, GMS_FUSED, BACKGROUND_PLAYBACK, PER_APP_SCENARIO, GNSS_INJECTION, SENSOR_SIMULATION }
enum class Availability { AVAILABLE, REQUIRES_ACTION, UNAVAILABLE, EXPERIMENTAL }
data class CapabilityStatus(val availability: Availability, val reason: String = "")
typealias BackendCapabilities = Map<Capability, CapabilityStatus>

sealed class BackendResult {
    data object Success : BackendResult()
    data class Failure(val stage: String, val reason: String, val suggestion: String = "") : BackendResult()
}

interface LocationBackend {
    val type: BackendType
    val capabilities: BackendCapabilities
    suspend fun prepare(): BackendResult
    suspend fun start(): BackendResult
    suspend fun publish(sample: LocationSample): BackendResult
    suspend fun pause(): BackendResult = BackendResult.Success
    suspend fun resume(): BackendResult = BackendResult.Success
    suspend fun stop(): BackendResult
    suspend fun release(): BackendResult
}
