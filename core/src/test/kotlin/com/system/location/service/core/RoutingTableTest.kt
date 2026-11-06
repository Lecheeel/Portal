package com.system.location.service.core

import com.system.location.service.core.routing.AppRoute
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingMode
import com.system.location.service.core.routing.RoutingTable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTableTest {
    private val perApp = RoutingConfig(
        mode = RoutingMode.PER_APP,
        globalScenarioId = "global",
        routes = listOf(
            AppRoute("com.example.map", "map-scenario"),
            AppRoute("com.example.route-by-uid", "uid-scenario", uid = 10_123),
            AppRoute("com.example.disabled", "ignored", enabled = false),
        ),
        deniedPackages = setOf("com.example.denied"),
    )

    @Test fun globalModeAlwaysUsesGlobalLocation() {
        val table = RoutingTable(RoutingConfig(mode = RoutingMode.GLOBAL, globalScenarioId = "x"))
        assertEquals(RoutingDecision.Global, table.decision(10_123, "com.example.map", false))
    }

    @Test fun exactPackageWinsOverUidAndIsCaseInsensitive() {
        val table = RoutingTable(perApp)
        assertEquals(RoutingDecision.Scenario("map-scenario"), table.decision(10_123, "COM.EXAMPLE.MAP", false))
    }

    @Test fun uidRouteIsUsedWhenPackageIsNotConfigured() {
        val table = RoutingTable(perApp)
        assertEquals(RoutingDecision.Scenario("uid-scenario"), table.decision(10_123, "com.other.app", false))
    }

    @Test fun systemCallersAndLowUidsBypassFakeRoutes() {
        val table = RoutingTable(perApp)
        assertEquals(RoutingDecision.Global, table.decision(1000, "com.example.map", true))
        assertEquals(RoutingDecision.Global, table.decision(1000, "com.example.map", false))
        assertEquals(RoutingDecision.Global, table.decision(10_123, "com.example.map", true))
    }

    @Test fun deniedPackageWinsOverRoutes() {
        val table = RoutingTable(perApp.copy(
            routes = perApp.routes + AppRoute("com.example.denied", "should-not-win"),
        ))
        assertEquals(RoutingDecision.Deny, table.decision(10_123, "com.example.denied", false))
    }

    @Test fun disabledRouteFallsBackToGlobalScenario() {
        val table = RoutingTable(perApp)
        assertEquals(RoutingDecision.Scenario("global"), table.decision(10_999, "com.example.disabled", false))
    }

    @Test fun noRouteAndNoGlobalScenarioUsesRealLocation() {
        val table = RoutingTable(perApp.copy(globalScenarioId = null))
        assertEquals(RoutingDecision.Global, table.decision(10_999, "com.unknown", false))
    }

    @Test fun invalidOrDuplicateRoutesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingTable(RoutingConfig(
                mode = RoutingMode.PER_APP,
                routes = listOf(AppRoute("com.example.map", "a"), AppRoute("COM.EXAMPLE.MAP", "b")),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingTable(RoutingConfig(
                mode = RoutingMode.PER_APP,
                routes = listOf(AppRoute("a", "s", uid = 10_123), AppRoute("b", "t", uid = 10_123)),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) { AppRoute("", "s") }
        assertThrows(IllegalArgumentException::class.java) { AppRoute("pkg", "") }
        assertThrows(IllegalArgumentException::class.java) { RoutingConfig(schemaVersion = 99) }
    }

    @Test fun configRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(RoutingConfig.serializer(), json.encodeToString(RoutingConfig.serializer(), perApp))
        assertEquals(perApp, decoded)
    }

    @Test fun fixedPointRouteReturnsPoint() {
        val point = com.system.location.service.core.geo.Wgs84(31.5, 121.5)
        val config = perApp.copy(routes = perApp.routes + AppRoute("com.example.fixed", point = point))
        val decision = RoutingTable(config).decision(10_999, "com.example.fixed", false)
        assertTrue(decision is RoutingDecision.Point)
        assertEquals(point, (decision as RoutingDecision.Point).point)
    }

    @Test fun routeRequiresScenarioOrPointAndNotBoth() {
        assertThrows(IllegalArgumentException::class.java) { AppRoute("com.example.empty") }
        assertThrows(IllegalArgumentException::class.java) {
            AppRoute("com.example.both", "s", point = com.system.location.service.core.geo.Wgs84(31.0, 121.0))
        }
    }
}
