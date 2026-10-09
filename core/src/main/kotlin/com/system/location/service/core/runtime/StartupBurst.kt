package com.system.location.service.core.runtime

/** Captures a single session. Pausing/stopping/replacing it makes all remaining burst ticks inert. */
class StartupBurst(private val scenarioId: String?, private val startedAt: Long?) {
    private var remaining = 5
    fun nextDelay(state: RuntimeState, normalIntervalMs: Long): Long {
        if (state.phase != RuntimePhase.RUNNING || state.scenarioId != scenarioId || state.startedAt != startedAt) {
            remaining = 0
            return normalIntervalMs
        }
        return if (remaining > 0) 80L else normalIntervalMs
    }
    fun accept(state: RuntimeState): Boolean {
        if (remaining <= 0 || state.phase != RuntimePhase.RUNNING || state.scenarioId != scenarioId || state.startedAt != startedAt) {
            remaining = 0; return false
        }
        remaining--
        return true
    }
}
