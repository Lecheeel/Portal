package com.system.location.service.ui.settings

import com.system.location.service.core.routing.RoutingConfig
import kotlinx.serialization.json.Json

/** An unset preference means default rules; malformed saved rules must still fail. */
internal fun readRoutingSettings(raw: String): RoutingConfig =
    if (raw.isBlank()) RoutingConfig() else Json.decodeFromString(RoutingConfig.serializer(), raw)
