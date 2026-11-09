package com.system.location.service.ui.home

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.repository.SavedRoute

/** The selected target is independent of the runtime's already-frozen active scenario. */
internal sealed interface HomeSelection {
    data class Point(val coordinate: Wgs84, val name: String?, val savedId: String? = null) : HomeSelection
    data class Route(val saved: SavedRoute) : HomeSelection
}

internal fun HomeSelection.refreshed(
    routes: List<SavedRoute>,
    locations: List<com.system.location.service.core.repository.SavedLocation>,
): HomeSelection? = when (this) {
    is HomeSelection.Route -> routes.firstOrNull { it.route.id == saved.route.id }?.let(HomeSelection::Route)
    is HomeSelection.Point -> if (savedId == null) this else locations.firstOrNull { it.id == savedId }
        ?.let { HomeSelection.Point(it.coordinate, it.name, it.id) }
}

internal suspend fun HomeSelection.start(
    point: suspend (HomeSelection.Point) -> Boolean,
    route: suspend (SavedRoute) -> Boolean,
): Boolean = when (this) {
    is HomeSelection.Point -> point(this)
    is HomeSelection.Route -> route(saved)
}
