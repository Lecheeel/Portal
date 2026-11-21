package com.system.location.service.gnss

import com.system.location.service.core.gnss.GnssSimulationMode

/** Fill only an untouched startup choice; explicit replay and disabling remain user choices. */
internal object SatelliteStartupDefaults {
    fun additions(preferences: Map<String, *>, rooted: Boolean, systemModuleActive: Boolean): Map<String, Any> {
        if (!rooted || !systemModuleActive || preferences.containsKey("gnssReplayOnStart")) return emptyMap()
        if (preferences["gnssSimulationMode"] == GnssSimulationMode.OFF.name) return emptyMap()
        return buildMap {
            put("gnssReplayOnStart", true)
            if (!preferences.containsKey("gnssSimulationMode")) {
                put("gnssSimulationMode", GnssSimulationMode.AUTONOMOUS.name)
            }
        }
    }
}
