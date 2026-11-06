package com.system.location.service.hook.security

import android.os.Bundle
import com.system.location.service.core.settings.PortalSettingsSchema

/** P5-08: reject invalid put_config values before they touch FakeLoc. */
object ConfigGate {
    fun valid(bundle: Bundle): Boolean {
        val schema = PortalSettingsSchema.schema
        return schema.keys().all { key ->
            !bundle.containsKey(key) || schema.validate(key, bundle.get(key)?.toString()) == null
        }
    }
}
