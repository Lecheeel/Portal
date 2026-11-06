package com.system.location.service.core

import com.system.location.service.core.routing.AppRoute
import com.system.location.service.core.routing.CallerRouter
import com.system.location.service.core.routing.PackageUidLookup
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingMode
import com.system.location.service.core.routing.RoutingTable
import org.junit.Assert.assertEquals
import org.junit.Test

class CallerRouterTest {
    private val table = RoutingTable(
        RoutingConfig(
            mode = RoutingMode.PER_APP,
            globalScenarioId = "global",
            routes = listOf(
                AppRoute("com.example.map", "map"),
                AppRoute("com.example.shared", "shared"),
            ),
            deniedPackages = setOf("com.example.denied"),
        ),
    )

    private class Lookup(
        private val packages: Map<Int, List<String>>,
        private val system: Set<String> = emptySet(),
    ) : PackageUidLookup {
        override fun packagesForUid(uid: Int): List<String> = packages[uid].orEmpty()
        override fun isSystemPackage(packageName: String): Boolean = packageName in system
    }

    @Test fun resolvesPackageRouteFromUid() {
        val router = CallerRouter(table, Lookup(mapOf(10_123 to listOf("com.example.map"))))
        assertEquals(RoutingDecision.Scenario("map"), router.decision(10_123))
    }

    @Test fun sharedUidMatchesAnyRoutedPackage() {
        val router = CallerRouter(table, Lookup(mapOf(10_123 to listOf("com.other", "com.example.shared"))))
        assertEquals(RoutingDecision.Scenario("shared"), router.decision(10_123))
    }

    @Test fun systemPackageInUidBypassesRoutes() {
        val router = CallerRouter(
            table,
            Lookup(mapOf(10_123 to listOf("com.example.map")), system = setOf("com.example.map")),
        )
        assertEquals(RoutingDecision.Global, router.decision(10_123))
    }

    @Test fun deniedPackageWins() {
        val router = CallerRouter(table, Lookup(mapOf(10_123 to listOf("com.example.denied"))))
        assertEquals(RoutingDecision.Deny, router.decision(10_123))
    }

    @Test fun unknownUidUsesGlobalScenario() {
        val router = CallerRouter(table, Lookup(emptyMap()))
        assertEquals(RoutingDecision.Scenario("global"), router.decision(10_999))
    }
}
