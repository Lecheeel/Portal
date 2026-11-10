package com.system.location.service.core.settings

enum class SettingType { BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, ENUM }

data class SettingSpec(
    val key: String,
    val type: SettingType,
    val defaultValue: String,
    val min: Double? = null,
    val max: Double? = null,
    val choices: Set<String> = emptySet(),
    val maxLength: Int? = null,
) {
    init {
        require(key.isNotBlank()) { "key must not be blank" }
        if (type == SettingType.ENUM) require(choices.isNotEmpty()) { "ENUM requires choices" }
        require(choices.isEmpty() || defaultValue in choices) { "default must be a choice" }
        require(maxLength == null || maxLength > 0) { "maxLength must be positive" }
    }
}

/** P5-08: one validated description of the Bundle/config keys shared by app and system_server. */
class SettingsSchema(specs: List<SettingSpec>) {
    private val byKey: Map<String, SettingSpec>

    init {
        require(specs.map { it.key }.distinct().size == specs.size) { "Duplicate setting key" }
        byKey = specs.associateBy { it.key }
    }

    fun keys(): Set<String> = byKey.keys

    fun spec(key: String): SettingSpec? = byKey[key]

    fun defaultValue(key: String): String? = byKey[key]?.defaultValue

    fun validate(key: String, raw: String?): String? {
        val spec = byKey[key] ?: return "Unknown setting: $key"
        if (raw == null) return null
        return validateValue(spec, raw)
    }

    fun coerce(key: String, raw: String?): String? {
        val spec = byKey[key] ?: return null
        if (raw == null || validateValue(spec, raw) != null) return spec.defaultValue
        return when (spec.type) {
            SettingType.BOOLEAN -> raw.lowercase()
            SettingType.ENUM -> raw
            SettingType.STRING -> raw
            else -> raw
        }
    }

    private fun validateValue(spec: SettingSpec, raw: String): String? = when (spec.type) {
        SettingType.BOOLEAN -> if (raw.lowercase() in setOf("true", "false")) null else "not a boolean"
        SettingType.INT -> {
            val value = raw.toIntOrNull() ?: return "not an int"
            inRange(spec, value.toDouble())
        }
        SettingType.LONG -> {
            val value = raw.toLongOrNull() ?: return "not a long"
            inRange(spec, value.toDouble())
        }
        SettingType.FLOAT -> {
            val value = raw.toFloatOrNull() ?: return "not a float"
            inRange(spec, value.toDouble())
        }
        SettingType.DOUBLE -> {
            val value = raw.toDoubleOrNull() ?: return "not a double"
            inRange(spec, value)
        }
        SettingType.STRING -> when {
            raw.isBlank() -> "blank string"
            spec.maxLength != null && raw.length > spec.maxLength -> "string too long"
            else -> null
        }
        SettingType.ENUM -> if (raw in spec.choices) null else "not a valid choice"
    }

    private fun inRange(spec: SettingSpec, value: Double): String? {
        if (!value.isFinite()) return "not finite"
        if (spec.min != null && value < spec.min) return "below minimum"
        if (spec.max != null && value > spec.max) return "above maximum"
        return null
    }
}

object PortalSettingsSchema {
    val schema = SettingsSchema(
        listOf(
            SettingSpec("altitude", SettingType.DOUBLE, "0.0", 0.0, 20_000.0),
            SettingSpec("speed", SettingType.DOUBLE, "3.05", 0.0, 1_000.0),
            SettingSpec("accuracy", SettingType.FLOAT, "25.0", 0.1, 10_000.0),
            SettingSpec("report_interval", SettingType.LONG, "100", 50.0, 1_000.0),
            SettingSpec("min_satellites", SettingType.INT, "12", 0.0, 128.0),
            SettingSpec("enable_mock_wifi", SettingType.BOOLEAN, "true"),
            SettingSpec("enable_debug_log", SettingType.BOOLEAN, "false"),
            SettingSpec("sim_ready", SettingType.BOOLEAN, "true"),
            SettingSpec("enable_agps", SettingType.BOOLEAN, "false"),
            SettingSpec("enable_nmea", SettingType.BOOLEAN, "true"),
            SettingSpec("disable_get_current_location", SettingType.BOOLEAN, "false"),
            SettingSpec("disable_register_location_listener", SettingType.BOOLEAN, "false"),
            SettingSpec("disable_fused_location", SettingType.BOOLEAN, "false"),
            SettingSpec("disable_request_geofence", SettingType.BOOLEAN, "false"),
            SettingSpec("disable_get_from_location", SettingType.BOOLEAN, "false"),
            SettingSpec("need_downgrade_to_2g", SettingType.BOOLEAN, "false"),
            SettingSpec("hide_mock", SettingType.BOOLEAN, "false"),
            SettingSpec("hook_wifi", SettingType.BOOLEAN, "true"),
            SettingSpec("enable_physical_noise", SettingType.BOOLEAN, "false"),
            SettingSpec("wifi_fingerprint_enabled", SettingType.BOOLEAN, "false"),
            SettingSpec("wifi_fingerprint_json", SettingType.STRING, "", maxLength = 262_144),
            SettingSpec("physical_noise_seed", SettingType.LONG, "0"),
            SettingSpec("speed_amplitude", SettingType.DOUBLE, "1.0", 0.0, 10.0),
            SettingSpec("sensor_cadence_scale", SettingType.DOUBLE, "1.0", 0.5, 2.0),
            SettingSpec("sensor_variation", SettingType.DOUBLE, "0.15", 0.0, 0.5),
            SettingSpec("environment_ap_count", SettingType.INT, "4", 1.0, 8.0),
            SettingSpec("cell_mode", SettingType.ENUM, "FULL_REPLAY", choices = setOf(
                "AUTO", "FULL_REPLAY", "NO_SIM_WIFI_ONLY", "LEGACY_CDMA",
            )),
            SettingSpec("environment_source", SettingType.ENUM, "AUTO", choices = setOf(
                "AUTO", "PROCEDURAL", "REPLAY",
            )),
            SettingSpec("gnss_model", SettingType.ENUM, "ORBITAL", choices = setOf(
                "ANALYTIC", "ORBITAL",
            )),
        ),
    )
}
