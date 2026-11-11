package com.system.location.service.data.persistence

import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.gnss.GnssFrame
import com.system.location.service.core.location.LocationSanitizer
import com.system.location.service.core.runtime.RuntimeSnapshot

/** Build one full wire batch from one SharedPreferences.all and one runtime fallback snapshot. */
object HookConfigurationPreferences {
    fun read(preferences: Map<String, *>, fallback: RuntimeSnapshot): Map<String, Any?> {
        val policy = fallback.configuration
        fun flag(key: String, default: Boolean) = preferences[key]?.let { it as Boolean } ?: default
        fun number(key: String, default: Float) = preferences[key]?.let { it as Float } ?: default
        fun integer(key: String, default: Int) = preferences[key]?.let { it as Int } ?: default
        fun text(key: String, default: String = "") = preferences[key]?.let { it as String } ?: default
        val interval = integer("reportDuration", 100).toLong().coerceIn(50, 1000)
        return mapOf(
            "altitude" to number("altitude", 0f).toDouble(),
            "speed" to number("speed", fallback.speedMps.toFloat()).toDouble(),
            "accuracy" to number("accuracy", LocationSanitizer.DEFAULT_HORIZONTAL_ACCURACY)
                .coerceIn(0.1f, 10_000f),
            "report_interval" to if (flag("loopBroadcastLocation", false)) interval else maxOf(500L, interval),
            // `disableWifiScan` is a legacy key whose historical true value
            // actually enabled replacement. Prefer the canonical key when it
            // exists, while preserving upgrades from older installations.
            "enable_mock_wifi" to (preferences["enableMockWifi"] as? Boolean
                ?: flag("disableWifiScan", policy.wifiEnabled)),
            "enable_debug_log" to flag("debug", policy.debugLog),
            "disable_get_current_location" to flag("disableGetCurrentLocation", policy.disableGetCurrentLocation),
            "disable_register_location_listener" to flag("disableRegitserLocationListener", policy.disableRegisterLocationListener),
            "disable_fused_location" to flag("disableFusedProvider", policy.disableFusedLocation),
            "need_downgrade_to_2g" to flag("needDowngradeToCdma", policy.downgradeToCdma),
            "enable_agps" to flag("enableAGPS", policy.allowPhysicalRawGnss),
            "enable_nmea" to flag("enableNMEA", true),
            "sensor_cadence_scale" to number("sensorCadenceScale", 1f).toDouble(),
            "sensor_variation" to number("sensorVariation", 0.15f).toDouble(),
            "disable_request_geofence" to !flag("enableRequestGeofence", !policy.disableRequestGeofence),
            "disable_get_from_location" to !flag("enableGetFromLocation", !policy.disableGetFromLocation),
            "enable_physical_noise" to flag("physicalNoise", fallback.physicalNoise),
            "sim_ready" to flag("simReady", true),
            "environment_ap_count" to integer("environmentApCount", 4)
                .coerceIn(EnvironmentConfig.MIN_AP_PER_CELL, EnvironmentConfig.MAX_AP_PER_CELL),
            "cell_mode" to text("cellMode", "FULL_REPLAY"),
            "environment_source" to text("environmentSourceMode", "AUTO"),
            "wifi_fingerprint_enabled" to flag("wifiFingerprintEnabled", false),
            // Full synchronization explicitly clears removed values; partial commands omit keys.
            "wifi_fingerprint_json" to text("wifiFingerprintCatalogJson").takeIf { it.isNotBlank() },
            "routing_json" to text("routingJson").takeIf { it.isNotBlank() },
            "environment_json" to text("environmentReplayJson").takeIf { it.isNotBlank() },
        ) + readGnss(preferences, fallback)
    }

    /** Same wire defaults for local prediction and configuration, independent of unrelated settings. */
    fun readGnss(preferences: Map<String, *>, fallback: RuntimeSnapshot): Map<String, Any> {
        val policy = fallback.configuration.gnssSimulation
        fun text(key: String, default: String) = preferences[key]?.let { it as String } ?: default
        return mapOf(
            "min_satellites" to (preferences["minSatelliteCount"]?.let { it as Int } ?: 0)
                .coerceIn(0, GnssFrame.MAX_SATELLITES),
            "gnss_mode" to text("gnssSimulationMode", policy.mode.name),
            "gnss_minimum_policy" to text("gnssMinimumPolicy", policy.minimumPolicy.name),
            "gnss_environment" to text("gnssSimulationEnvironment", policy.environment.name),
            "gnss_virtual_seed" to (preferences["gnssVirtualSeed"]?.let { it as Long } ?: policy.virtualSeed),
        )
    }
}
