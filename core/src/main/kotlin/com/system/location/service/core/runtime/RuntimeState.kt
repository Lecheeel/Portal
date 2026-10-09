package com.system.location.service.core.runtime

import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import kotlinx.serialization.Serializable

enum class RuntimePhase { IDLE, PREPARING, READY, RUNNING, PAUSED, STOPPING, STOPPED, ERROR }

data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val backend: BackendType = BackendType.MOCK_PROVIDER,
    val scenarioId: String? = null,
    val scenarioName: String? = null,
    val routeId: String? = null,
    val currentPoint: Int = 0,
    val sample: LocationSample? = null,
    val progress: Double = 0.0,
    val startedAt: Long? = null,
    val lastUpdateAt: Long? = null,
    val error: BackendResult.Failure? = null,
    val capabilities: BackendCapabilities = emptyMap(),
    val configuredSpeedMps: Double? = null,
    val submissionHealth: SubmissionHealth = SubmissionHealth(),
) {
    val isActive get() = phase in setOf(RuntimePhase.PREPARING, RuntimePhase.READY, RuntimePhase.RUNNING, RuntimePhase.PAUSED, RuntimePhase.STOPPING)
}

data class SubmissionHealth(val count: Long = 0, val lastGapMs: Double = 0.0, val maxGapMs: Double = 0.0,
    val lastLatencyMs: Double = 0.0, val maxLatencyMs: Double = 0.0, val delayedCount: Long = 0)

@Serializable data class DiagnosticEvent(val timestamp: Long, val backend: BackendType, val stage: String,
    val result: String, val reason: String, val suggestion: String = "")

/** Injectable clocks make lifecycle tests deterministic and avoid wall-clock movement jumps. */
interface RuntimeClock {
    fun nanos(): Long
    fun millis(): Long
}
