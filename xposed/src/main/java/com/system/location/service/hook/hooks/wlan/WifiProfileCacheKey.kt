package com.system.location.service.hook.hooks.wlan

import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.geo.Wgs84

internal data class WifiProfileCacheKey(
    val point: Wgs84,
    val seed: Long,
    val apCount: Int,
    val environmentVersion: Long,
    val sourceMode: EnvironmentSourceMode,
    val fingerprintEnabled: Boolean,
    val fingerprintRevision: Long,
)
