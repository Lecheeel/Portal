package com.system.location.service.ui.home

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.repository.SavedRoute
import com.system.location.service.core.scenario.Route
import com.system.location.service.core.scenario.RouteMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeSelectionTest {
    private val saved = SavedRoute(Route("recording", "录制路线", listOf(Wgs84(30.0, 120.0), Wgs84(30.1, 120.1))), RouteMode.PING_PONG)

    @Test fun pointSelectionStartsOnlyPoint() = runBlocking {
        val selected = HomeSelection.Point(Wgs84(31.0, 121.0), "新位置", "point-id")
        var started: HomeSelection.Point? = null
        selected.start(point = { started = it; true }, route = { error("Point must not start a route") })
        assertEquals(selected, started)
    }

    @Test fun deletedSavedRouteClearsSelectionRatherThanFallingBackToFirstPoint() {
        assertNull(HomeSelection.Route(saved).refreshed(emptyList(), emptyList()))
    }

    @Test fun deletedSavedPointClearsSelection() {
        val selected = HomeSelection.Point(Wgs84(31.0, 121.0), "位置", "missing")
        assertNull(selected.refreshed(emptyList(), emptyList()))
    }

    @Test fun unsavedMapCoordinateSurvivesLibraryRefresh() {
        val selected = HomeSelection.Point(Wgs84(31.0, 121.0), "地图坐标")
        assertEquals(selected, selected.refreshed(emptyList(), emptyList()))
    }

    @Test fun refreshedRouteUsesCurrentPlaybackModeAndGeometry() {
        val changed = saved.copy(mode = RouteMode.LOOP,
            route = saved.route.copy(points = listOf(Wgs84(31.0, 121.0), Wgs84(32.0, 122.0))))
        assertEquals(HomeSelection.Route(changed), HomeSelection.Route(saved).refreshed(listOf(changed), emptyList()))
    }

    @Test fun selectedRouteStartsRouteWithOriginalGeometryAndMode() = runBlocking {
        var started: SavedRoute? = null
        HomeSelection.Route(saved).start(
            point = { error("Route selection must not start a point") },
            route = { started = it; true },
        )
        assertEquals(saved, started)
    }
}
