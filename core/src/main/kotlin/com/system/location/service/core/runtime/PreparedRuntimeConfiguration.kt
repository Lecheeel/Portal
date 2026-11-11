package com.system.location.service.core.runtime

import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.environment.WifiFingerprintImporter
import com.system.location.service.core.gnss.CapturedGnssSource
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.settings.PortalSettingsSchema
import com.system.location.service.core.settings.SettingType
import kotlinx.serialization.json.Json

/**
 * Prepared outside the publication CAS: all wire values are copied, type checked and decoded here.
 * Missing keys preserve the latest state; explicit null clears nullable evidence/configuration.
 * [applyTo] is side-effect free and may be retried against a newer sample or control state.
 */
class PreparedRuntimeConfiguration private constructor(
    private val values: Map<String, Any?>,
    private val source: CapturedGnssSource?,
    private val captureRejected: Boolean,
    private val routing: RoutingConfig?,
    private val environment: EnvironmentProfile?,
) {
    @Suppress("UNCHECKED_CAST")
    private fun <T> value(key: String, fallback: T): T =
        if (values.containsKey(key)) values[key] as T else fallback

    fun applyTo(current: RuntimeSnapshot): RuntimeSnapshot {
        val old = current.configuration
        val policy = old.copy(
            enabled = value("enable", old.enabled),
            wifiEnabled = value("enable_mock_wifi", old.wifiEnabled),
            debugLog = value("enable_debug_log", old.debugLog),
            disableGetCurrentLocation = value("disable_get_current_location", old.disableGetCurrentLocation),
            disableRegisterLocationListener = value("disable_register_location_listener", old.disableRegisterLocationListener),
            disableFusedLocation = value("disable_fused_location", old.disableFusedLocation),
            downgradeToCdma = value("need_downgrade_to_2g", old.downgradeToCdma),
            minimumSatellites = value("min_satellites", old.minimumSatellites),
            allowPhysicalRawGnss = value("enable_agps", old.allowPhysicalRawGnss),
            nmeaEnabled = value("enable_nmea", old.nmeaEnabled),
            hideMock = value("hide_mock", old.hideMock),
            hookWifi = value("hook_wifi", old.hookWifi),
            disableRequestGeofence = value("disable_request_geofence", old.disableRequestGeofence),
            disableGetFromLocation = value("disable_get_from_location", old.disableGetFromLocation),
            reportIntervalMs = value("report_interval", old.reportIntervalMs),
            sensorCadenceScale = value("sensor_cadence_scale", old.sensorCadenceScale),
            sensorVariation = value("sensor_variation", old.sensorVariation),
            speedAmplitude = value("speed_amplitude", old.speedAmplitude),
            wifiFingerprintEnabled = value("wifi_fingerprint_enabled", old.wifiFingerprintEnabled),
            wifiFingerprintCatalogJson = value("wifi_fingerprint_json", old.wifiFingerprintCatalogJson),
            gnssSource = if ("gnss_capture" in values) source else old.gnssSource,
            gnssCaptureRejected = if ("gnss_capture" in values) captureRejected else old.gnssCaptureRejected,
        )
        return current.copy(
            configRevision = Math.addExact(current.configRevision, 1),
            configuration = policy,
            speedMps = if (!policy.enabled) value("speed", current.speedMps) else current.speedMps,
            altitudeMeters = value("altitude", current.altitudeMeters),
            accuracyMeters = value("accuracy", current.accuracyMeters),
            physicalNoise = value("enable_physical_noise", current.physicalNoise),
            physicalNoiseSeed = value("physical_noise_seed", current.physicalNoiseSeed),
            simReady = value("sim_ready", current.simReady),
            environmentApCount = value("environment_ap_count", current.environmentApCount),
            cellMode = value("cell_mode", current.cellMode),
            environmentSourceMode = value("environment_source", current.environmentSourceMode),
            routing = if ("routing_json" in values) routing else current.routing,
            environment = if ("environment_json" in values) environment else current.environment,
            environmentVersion = if ("environment_json" in values)
                Math.addExact(current.environmentVersion, 1) else current.environmentVersion,
        )
    }

    companion object {
        private val nullableStrings = setOf("gnss_capture", "routing_json", "environment_json", "wifi_fingerprint_json")
        private val extraKeys = nullableStrings + "enable"
        val keys: Set<String> = PortalSettingsSchema.schema.keys() + extraKeys

        fun prepare(input: Map<String, Any?>, previousSource: CapturedGnssSource? = null): PreparedRuntimeConfiguration {
            val schema = PortalSettingsSchema.schema
            val values = input.filterKeys { it in keys }.toMutableMap()
            values.toMap().forEach { (key, raw) ->
                val type = schema.spec(key)?.type ?: if (key == "enable") SettingType.BOOLEAN else SettingType.STRING
                if (key in nullableStrings && raw == null) return@forEach
                val validType = when (type) {
                    SettingType.BOOLEAN -> raw is Boolean
                    SettingType.INT -> raw is Int
                    SettingType.LONG -> raw is Long
                    SettingType.FLOAT -> raw is Float
                    SettingType.DOUBLE -> raw is Double
                    SettingType.STRING, SettingType.ENUM -> raw is String
                }
                require(validType) { "Invalid wire type for $key" }
                if (type == SettingType.STRING) require((raw as String).length <= 262_144) { "$key too large" }
                if (key in nullableStrings && (raw as String).isBlank()) {
                    values[key] = null
                } else {
                    if (schema.spec(key) != null) require(schema.validate(key, raw.toString()) == null) {
                        "Invalid value for $key"
                    }
                }
            }
            var captureRejected = false
            val source = if ("gnss_capture" in values) try {
                CapturedGnssSource.prepare(values["gnss_capture"] as String?, previousSource)
            } catch (_: Exception) {
                // Only optional evidence is recoverable. Wire types, sizes, required settings,
                // authorization and transport errors still follow their original failure paths.
                captureRejected = true
                previousSource
            } else null
            val routing = (values["routing_json"] as String?)?.let {
                Json.decodeFromString(RoutingConfig.serializer(), it)
            }
            val environment = (values["environment_json"] as String?)?.let(EnvironmentProfileCodec::decode)
            (values["wifi_fingerprint_json"] as String?)?.let { WifiFingerprintImporter.importJson(it) }
            (values["cell_mode"] as String?)?.let { values["cell_mode"] = CellMode.valueOf(it) }
            (values["environment_source"] as String?)?.let { values["environment_source"] = EnvironmentSourceMode.valueOf(it) }
            return PreparedRuntimeConfiguration(values.toMap(), source, captureRejected, routing, environment)
        }
    }
}
