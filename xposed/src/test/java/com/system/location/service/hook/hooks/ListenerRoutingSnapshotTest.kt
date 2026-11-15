package com.system.location.service.hook.hooks

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.routing.AppRoute
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerRoutingSnapshotTest {
    @Test
    fun decisionUsesTheRoutingConfigCapturedForThisRequest() {
        val captured = RoutingConfig(mode = RoutingMode.PER_APP, routes = listOf(
            AppRoute(packageName = "target.app", point = Wgs84(31.0, 121.0)),
        ))

        assertEquals(RoutingDecision.Point(Wgs84(31.0, 121.0)),
            ListenerRouting.decisionFor("target.app", 10_123, captured))
        assertEquals(RoutingDecision.Global,
            ListenerRouting.decisionFor("target.app", 10_123, RoutingConfig()))
    }

    @Test
    fun explicitDenyIsPreservedFromTheCapturedConfig() {
        val captured = RoutingConfig(mode = RoutingMode.PER_APP, deniedPackages = setOf("target.app"))

        assertEquals(RoutingDecision.Deny, ListenerRouting.decisionFor("target.app", 10_123, captured))
    }

    @Test
    fun unresolvedCallerCannotInheritTheGlobalScenarioRoute() {
        val captured = RoutingConfig(mode = RoutingMode.PER_APP, globalScenarioId = "fallback")

        assertEquals(RoutingDecision.Deny,
            ListenerRouting.decisionForPackages(10_123, captured, emptyList()))
    }

    @Test
    fun verifiedSystemCallerKeepsTheGlobalRoute() {
        val captured = RoutingConfig(mode = RoutingMode.PER_APP, deniedPackages = setOf("system.app"))

        assertEquals(RoutingDecision.Global,
            ListenerRouting.decisionForPackages(10_123, captured, listOf("system.app")) { true })
    }
}
