package com.system.location.service.ui.mock

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.planning.DrawingMode
import com.system.location.service.core.planning.DraftPhase
import com.system.location.service.core.planning.RouteDraft
import com.system.location.service.core.scenario.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEditorActionsTest {
    private val start = Gcj02(30.0, 120.0)
    private val end = Gcj02(30.1, 120.1)

    @Test fun `failed persistence cannot save a stale route`() {
        val route = Route("saved", "route", listOf(Wgs84(30.0, 120.0), Wgs84(30.1, 120.1)))
        val actions = RouteEditorActions.from(RouteDraft(route = route, phase = DraftPhase.ERROR))
        assertFalse(actions.canSave)
    }

    @Test fun `restoration blocks editor actions until saved draft arrives`() {
        val actions = RouteEditorActions.from(RouteDraft(message = "正在恢复草稿", start = start, end = end))
        assertTrue(actions.restoring)
        assertFalse(actions.canPlan)
        assertFalse(actions.canUndo)
        assertFalse(actions.canSave)
    }

    @Test fun `planning cannot be submitted twice`() {
        val draft = RouteDraft(start = start, end = end, phase = DraftPhase.ENDPOINTS)
        assertTrue(RouteEditorActions.from(draft).canPlan)
        val planning = RouteEditorActions.from(draft.copy(phase = DraftPhase.PLANNING))
        assertTrue(planning.planning)
        assertFalse(planning.canPlan)
        assertFalse(planning.canSave)
    }

    @Test fun `planning requires two different endpoints and plan mode`() {
        assertFalse(RouteEditorActions.from(RouteDraft(start = start)).canPlan)
        assertFalse(RouteEditorActions.from(RouteDraft(start = start, end = start)).canPlan)
        assertFalse(RouteEditorActions.from(RouteDraft(start = start, end = end, mode = DrawingMode.FREEHAND)).canPlan)
    }

    @Test fun `one manual point can be undone or cleared before a route exists`() {
        val actions = RouteEditorActions.from(RouteDraft(mode = DrawingMode.POINTS, start = start, strokes = listOf(listOf(start))))
        assertTrue(actions.hasGeometry)
        assertTrue(actions.canUndo)
        assertFalse(actions.canSave)
        assertFalse(RouteEditorActions.from(RouteDraft()).hasGeometry)
    }

    @Test fun `only planned geometry can be saved`() {
        val route = Route("saved", "route", listOf(Wgs84(30.0, 120.0), Wgs84(30.1, 120.1)))
        for (phase in DraftPhase.entries) {
            val actions = RouteEditorActions.from(RouteDraft(route = route, phase = phase))
            if (phase == DraftPhase.PLANNED) assertTrue(actions.canSave) else assertFalse(actions.canSave)
        }
        assertFalse(RouteEditorActions.from(RouteDraft(phase = DraftPhase.PLANNED)).canSave)
    }
}
