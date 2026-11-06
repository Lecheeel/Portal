package com.system.location.service.hook.security

import android.os.Bundle
import com.system.location.service.core.settings.PortalSettingsSchema

/** P5-08: reject invalid put_config values before they touch FakeLoc. */
object ConfigGate {
    private val keys = listOf(
        "altitude", "speed", "accuracy", "report_interval", "min_satellites",
        "environment_ap_count", "cell_mode", "environment_source", "gnss_model", "sim_ready",
    )

    fun valid(bundle: Bundle): Boolean {
        val schema = PortalSettingsSchema.schema
        return keys.all { key ->
            !bundle.containsKey(key) || schema.validate(key, bundle.get(key)?.toString()) == null
        }
    }
}
