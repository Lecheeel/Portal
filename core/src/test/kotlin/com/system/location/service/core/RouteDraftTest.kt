package com.system.location.service.core

import com.system.location.service.core.geo.*
import com.system.location.service.core.planning.*
import com.system.location.service.core.repository.DocumentStore
import org.junit.Assert.*
import org.junit.Test

class RouteDraftTest {
    private class Store : DocumentStore {
        var text: String? = null
        override fun read() = text
        override fun writeAtomically(text: String) { this.text = text }
    }
    private val start = Gcj02(25.0, 119.0)
    private val mid = Gcj02(25.1, 119.1)
    private val end = Gcj02(25.2, 119.2)
    private fun ready(store: Store = Store()) = RouteDraftController(store).apply { pick(start); pick(end) }
    @Test fun assemblesAllSequentialStepsAndDeduplicatesOnlyAdjacentPoints() {
        val c = ready(); val request = c.beginPlan()!!
        assertTrue(c.complete(request, listOf(listOf(start, mid), listOf(mid, end))))
        assertEquals(listOf(start, mid, end).map(CoordinateTransform::toWgs84), c.state.value.route!!.points)
    }
    @Test fun endpointChangesAndCancelRejectLatePlanningResults() {
        val c = ready(); val old = c.beginPlan()!!
        c.pick(mid)
        assertFalse(c.complete(old, listOf(listOf(start, end))))
        val next = c.beginPlan()!!; c.cancel()
        assertFalse(c.complete(next, listOf(listOf(start, mid))))
        assertEquals(DraftPhase.EMPTY, c.state.value.phase)
    }
    @Test fun cameraAndRouteSurviveControllerRecreation() {
        val store = Store(); val c = ready(store); val request = c.beginPlan()!!
        c.complete(request, listOf(listOf(start, end)))
        c.camera(MapCamera(mid, 16f, 180f, 30f))
        val restored = RouteDraftController(store)
        assertEquals(c.state.value, restored.state.value)
        val frozen = restored.state.value.route!!.frozen()
        restored.pick(mid)
        assertEquals(listOf(start, end).map(CoordinateTransform::toWgs84), frozen.points)
    }
    @Test fun inFlightPlanningRestoresAsInterruptedAndEmptyResultsAreFailures() {
        val store = Store(); val c = ready(store); val request = c.beginPlan()!!
        assertEquals(DraftPhase.ENDPOINTS, RouteDraftController(store).state.value.phase)
        assertFalse(c.complete(request, emptyList()))
        assertEquals(DraftPhase.ERROR, c.state.value.phase); assertNull(c.state.value.route)
        val retry = c.beginPlan()!!
        assertTrue(c.complete(retry, listOf(listOf(start, end))))
    }
    @Test fun laterPlanWinsEvenWhenEndpointsDidNotChange() {
        val c = ready(); val old = c.beginPlan()!!; val latest = c.beginPlan()!!
        c.fail(old, "old network failure")
        assertFalse(c.complete(old, listOf(listOf(start, mid))))
        assertTrue(c.complete(latest, listOf(listOf(start, end))))
    }
}
