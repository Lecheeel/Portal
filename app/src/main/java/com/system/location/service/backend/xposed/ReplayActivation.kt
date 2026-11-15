package com.system.location.service.backend.xposed

/** Apply both values, including false, so switches cannot leak from a previous session. */
internal object ReplayActivation {
    fun apply(
        environment: Boolean,
        satellite: Boolean,
        setEnvironment: (Boolean) -> Boolean,
        setSatellite: (Boolean) -> Boolean,
    ): Boolean {
        if (!setEnvironment(environment)) return false
        return setSatellite(satellite)
    }
}
