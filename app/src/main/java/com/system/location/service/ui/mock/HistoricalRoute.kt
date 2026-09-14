package com.system.location.service.ui.mock

import kotlinx.serialization.Serializable
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.data.repository.stableRecordId

@Serializable
data class HistoricalRoute(
    val name: String,
    val route: List<Pair<Double, Double>>,
    val id: String = stableRecordId("route", "$name:$route"),
    val mode: RouteMode = RouteMode.ONCE,
    val favorite: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "名称不能为空" }
        RouteJson.validatePoints(route)
    }
}

