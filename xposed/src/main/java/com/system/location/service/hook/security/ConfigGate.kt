package com.system.location.service.hook.security

import android.os.Bundle
import com.system.location.service.core.settings.PortalSettingsSchema

/** P5-08: reject invalid put_config values before they touch FakeLoc. */
object ConfigGate {
    private val keys = listOf(
        "altitude", "speed", "accuracy", "report_interval", "min_satellites",
        "environment_ap_count", "cell_mode", "environment_source", "gnss_model", "sim_ready",
        "enable_agps", "enable_nmea", "disable_get_current_location",
        "disable_register_location_listener", "disable_fused_location",
        "disable_request_geofence", "disable_get_from_location", "need_downgrade_to_2g",
        "hide_mock", "hook_wifi", "enable_physical_noise", "physical_noise_seed",
        "speed_amplitude",
    )

    fun valid(bundle: Bundle): Boolean {
        val schema = PortalSettingsSchema.schema
        return keys.all { key ->
            !bundle.containsKey(key) || schema.validate(key, bundle.get(key)?.toString()) == null
        }
    }
}
