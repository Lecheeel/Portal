package com.system.location.service.ui.mock

import kotlinx.serialization.Serializable

@Serializable
data class HistoricalRoute(
    val name: String,
    val route: List<Pair<Double, Double>>
) {
    init {
        require(name.isNotBlank()) { "名称不能为空" }
        RouteJson.validatePoints(route)
    }
}

