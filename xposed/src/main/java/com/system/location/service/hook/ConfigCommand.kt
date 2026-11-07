package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.gnss.GnssModelMode
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.hook.security.ConfigGate
import com.system.location.service.hook.utils.FakeLoc
import kotlinx.serialization.json.Json

/** P5-08: config command extracted from RemoteCommandHandler; behavior preserved. */
object ConfigCommand {
    fun apply(rely: Bundle): Boolean {
        if (!ConfigGate.valid(rely)) return false
        FakeLoc.enable = rely.getBoolean("enable", FakeLoc.enable)
        FakeLoc.enableMockWifi = rely.getBoolean("enable_mock_wifi", FakeLoc.enableMockWifi)
        FakeLoc.speed = rely.getDouble("speed", FakeLoc.speed)
        FakeLoc.altitude = rely.getDouble("altitude", FakeLoc.altitude)
        FakeLoc.accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)
        FakeLoc.enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
        FakeLoc.disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
        FakeLoc.disableRegisterLocationListener =
            rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
        FakeLoc.disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
        FakeLoc.needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
        val minSatellites = rely.getInt("min_satellites", 12)
        FakeLoc.minSatellites = if (minSatellites < 0) 12 else minSatellites
        FakeLoc.enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
        FakeLoc.enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
        FakeLoc.disableRequestGeofence = rely.getBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
        FakeLoc.disableGetFromLocation = rely.getBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)
        FakeLoc.enablePhysicalNoise = rely.getBoolean("enable_physical_noise", FakeLoc.enablePhysicalNoise)
        FakeLoc.physicalNoiseSeed = rely.getLong("physical_noise_seed", FakeLoc.physicalNoiseSeed)
        FakeLoc.simReady = rely.getBoolean("sim_ready", FakeLoc.simReady)
        FakeLoc.gnssModelMode = runCatching {
            GnssModelMode.valueOf(rely.getString("gnss_model") ?: FakeLoc.gnssModelMode.name)
        }.getOrDefault(FakeLoc.gnssModelMode)
        FakeLoc.environmentApCount = rely.getInt("environment_ap_count", FakeLoc.environmentApCount).coerceIn(1, 8)
        FakeLoc.cellMode = runCatching {
            CellMode.valueOf(rely.getString("cell_mode") ?: FakeLoc.cellMode.name)
        }.getOrDefault(FakeLoc.cellMode)
        FakeLoc.environmentSourceMode = runCatching {
            EnvironmentSourceMode.valueOf(
                rely.getString("environment_source") ?: FakeLoc.environmentSourceMode.name,
            )
        }.getOrDefault(FakeLoc.environmentSourceMode)
        FakeLoc.routingConfig = rely.getString("routing_json")
            ?.takeIf { it.isNotBlank() }
            ?.let { json -> runCatching { Json.decodeFromString(RoutingConfig.serializer(), json) }.getOrNull() }
        FakeLoc.replayEnvironment = rely.getString("environment_json")
            ?.takeIf { it.isNotBlank() }
            ?.let { json -> runCatching { EnvironmentProfileCodec.decode(json) }.getOrNull() }
        FakeLoc.reportIntervalMs = rely.getLong("report_interval", FakeLoc.reportIntervalMs).coerceIn(50, 1000)
        FakeLoc.wifiFingerprintEnabled = rely.getBoolean("wifi_fingerprint_enabled", false)
        FakeLoc.wifiFingerprintCatalogJson = rely.getString("wifi_fingerprint_json")
            ?.takeIf { it.isNotBlank() }
        if (FakeLoc.enable) LocationTicker.start()
        return true
    }
}
