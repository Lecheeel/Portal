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
    @Test fun manualPointsNeedTwoDistinctPointsAndUndoRestoresPreviousGeometry() {
        val c = RouteDraftController(Store())
        c.mode(DrawingMode.POINTS)
        c.pick(start); c.pick(start)
        assertNull(c.state.value.route)
        c.pick(mid); c.pick(end)
        val frozen = c.state.value.route!!.frozen()
        c.undo()
        assertEquals(listOf(start, mid).map(CoordinateTransform::toWgs84), c.state.value.route!!.points)
        assertEquals(3, frozen.points.size)
        c.undo()
        assertNull(c.state.value.route)
        assertNull(c.beginPlan())
    }
    @Test fun freehandPersistsEveryStrokeAndUndoRemovesOneWholeStroke() {
        val store = Store(); val c = RouteDraftController(store)
        c.mode(DrawingMode.FREEHAND)
        val input = mutableListOf(start, start, mid)
        c.appendStroke(input)
        input.clear()
        c.appendStroke(listOf(mid, end))
        val restored = RouteDraftController(store)
        assertEquals(DrawingMode.FREEHAND, restored.state.value.mode)
        assertEquals(listOf(start, mid, end).map(CoordinateTransform::toWgs84), restored.state.value.route!!.points)
        restored.undo()
        assertEquals(listOf(start, mid).map(CoordinateTransform::toWgs84), restored.state.value.route!!.points)
        restored.undo()
        assertNull(restored.state.value.route)
        assertTrue(restored.state.value.strokes.isEmpty())
    }
    @Test fun modeSwitchRejectsInFlightPlanAndClearKeepsModeAndCamera() {
        val c = ready(); val request = c.beginPlan()!!
        val camera = MapCamera(mid, 16f, 0f, 0f)
        c.camera(camera); c.mode(DrawingMode.FREEHAND)
        c.appendStroke(listOf(start, end))
        assertFalse(c.complete(request, listOf(listOf(start, mid, end))))
        assertEquals(2, c.state.value.route!!.points.size)
        c.cancel()
        assertEquals(DrawingMode.FREEHAND, c.state.value.mode)
        assertEquals(camera, c.state.value.camera)
        assertTrue(c.state.value.strokes.isEmpty())
        c.mode(DrawingMode.PLAN); c.pick(start); c.pick(end)
        assertNotNull(c.beginPlan())
    }
    @Test fun oversizeStrokeLeavesPreviousDraftIntact() {
        val c = RouteDraftController(Store()); c.mode(DrawingMode.FREEHAND)
        c.appendStroke(listOf(start, end))
        val before = c.state.value
        try {
            c.appendStroke(List(10_000) { if (it % 2 == 0) start else mid })
            fail("Should reject oversized draft")
        } catch (_: IllegalArgumentException) { assertEquals(before, c.state.value) }
    }
}
