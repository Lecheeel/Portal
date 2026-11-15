package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.routing.RoutingDecision

internal object TelephonyCallerPolicy {
    fun shouldRewrite(
        simulationEnabled: Boolean,
        systemCaller: Boolean,
        route: RoutingDecision,
    ): Boolean = simulationEnabled && !systemCaller && route != RoutingDecision.Deny
}
