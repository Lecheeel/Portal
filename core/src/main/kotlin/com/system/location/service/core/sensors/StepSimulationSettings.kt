package com.system.location.service.core.sensors

import kotlinx.serialization.Serializable

@Serializable
enum class StepSimulationMethod { XPOSED, NATIVE }

@Serializable
data class StepSimulationSettings(
    val enabled: Boolean = true,
    val method: StepSimulationMethod = StepSimulationMethod.XPOSED,
    val automatic: Boolean = true,
    val manualStepsPerMinute: Int = DEFAULT_STEPS_PER_MINUTE,
) {
    init {
        require(manualStepsPerMinute in 1..MAX_STEPS_PER_MINUTE)
    }

    val usesNative: Boolean get() = enabled && method == StepSimulationMethod.NATIVE
    val usesXposed: Boolean get() = enabled && method == StepSimulationMethod.XPOSED
    val fixedCadence: Int? get() = manualStepsPerMinute.takeUnless { automatic }

    companion object {
        const val DEFAULT_STEPS_PER_MINUTE = 120
        const val MAX_STEPS_PER_MINUTE = 220
    }
}
