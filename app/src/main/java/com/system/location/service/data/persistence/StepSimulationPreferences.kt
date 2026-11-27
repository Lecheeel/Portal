package com.system.location.service.data.persistence

import android.content.SharedPreferences
import androidx.core.content.edit
import com.system.location.service.core.sensors.StepSimulationMethod
import com.system.location.service.core.sensors.StepSimulationSettings

internal object StepSimulationPreferences {
    private const val ENABLED = "step_simulation_enabled"
    private const val METHOD = "step_simulation_method"
    private const val AUTOMATIC = "step_simulation_automatic"
    private const val CADENCE = "step_simulation_cadence"
    private val defaults = StepSimulationSettings()

    val keys = setOf(ENABLED, METHOD, AUTOMATIC, CADENCE)

    fun read(values: Map<String, *>): StepSimulationSettings = StepSimulationSettings(
        enabled = values[ENABLED] as? Boolean ?: defaults.enabled,
        method = StepSimulationMethod.entries.firstOrNull { it.name == values[METHOD] } ?: defaults.method,
        automatic = values[AUTOMATIC] as? Boolean ?: defaults.automatic,
        manualStepsPerMinute = (values[CADENCE] as? Int)?.takeIf {
            it in 1..StepSimulationSettings.MAX_STEPS_PER_MINUTE
        } ?: defaults.manualStepsPerMinute,
    )

    fun write(preferences: SharedPreferences, settings: StepSimulationSettings) {
        preferences.edit {
            putBoolean(ENABLED, settings.enabled)
            putString(METHOD, settings.method.name)
            putBoolean(AUTOMATIC, settings.automatic)
            putInt(CADENCE, settings.manualStepsPerMinute)
        }
    }

    fun wire(settings: StepSimulationSettings): Map<String, Any> = mapOf(
        ENABLED to settings.enabled,
        METHOD to settings.method.name,
        AUTOMATIC to settings.automatic,
        CADENCE to settings.manualStepsPerMinute,
    )
}
