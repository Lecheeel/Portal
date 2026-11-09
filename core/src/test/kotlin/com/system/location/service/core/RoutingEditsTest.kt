package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.routing.*
import org.junit.Assert.*
import org.junit.Test

class RoutingEditsTest {
    @Test fun exclusionEditsPreservePointAndScenarioAssignments() {
        val original = RoutingConfig(
            mode = RoutingMode.PER_APP,
            globalScenarioId = "fallback",
            routes = listOf(AppRoute("map", point = Wgs84(30.0, 120.0)), AppRoute("runner", "route")),
        )
        val updated = original.withDeniedPackages(setOf("denied"))
        assertEquals(original.routes, updated.routes)
        assertEquals("fallback", updated.globalScenarioId)
        assertEquals(RoutingDecision.Deny, RoutingTable(updated).decision(10_001, "denied", false))
        val cleared = updated.withDeniedPackages(emptySet())
        assertEquals(RoutingMode.PER_APP, cleared.mode)
        assertEquals(RoutingDecision.Point(Wgs84(30.0, 120.0)), RoutingTable(cleared).decision(10_001, "map", false))
        assertEquals(RoutingDecision.Scenario("fallback"), RoutingTable(cleared).decision(10_001, "other", false))
    }

    @Test fun clearingOnlyExclusionsReturnsToGlobalMode() {
        assertEquals(RoutingMode.GLOBAL, RoutingConfig().withDeniedPackages(setOf("app"))
            .withDeniedPackages(emptySet()).mode)
    }

    @Test fun deselectionRemovesOnlyAssignmentsForEditedPoint() {
        val point = Wgs84(30.0, 120.0)
        val other = Wgs84(31.0, 121.0)
        val original = RoutingConfig(mode = RoutingMode.PER_APP,
            routes = listOf(AppRoute("map", point = point), AppRoute("runner", point = other)),
            deniedPackages = setOf("denied"))
        val updated = original.withPointAssignments(point, emptySet())
        val table = RoutingTable(updated)
        assertEquals(RoutingDecision.Global, table.decision(10_001, "map", false))
        assertEquals(RoutingDecision.Point(other), table.decision(10_001, "runner", false))
        assertEquals(RoutingDecision.Deny, table.decision(10_001, "denied", false))
    }

    @Test fun reassigningAppToAnotherPointCreatesOnlyOneRule() {
        val point = Wgs84(30.0, 120.0)
        val updated = RoutingConfig(routes = listOf(AppRoute("MAP", "scenario")))
            .withPointAssignments(point, setOf("map", "MAP"))
        assertEquals(1, updated.routes.size)
        assertEquals(RoutingDecision.Point(point), RoutingTable(updated).decision(10_001, "MAP", false))
        assertEquals(RoutingMode.GLOBAL, updated.withPointAssignments(point, emptySet()).mode)
    }
}
