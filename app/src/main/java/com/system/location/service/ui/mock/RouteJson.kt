package com.system.location.service.ui.mock

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Route persistence uses generated serializers, including Kotlin's built-in Pair serializer. */
object RouteJson {
    fun encodeRoutes(routes: List<HistoricalRoute>): String = Json.encodeToString(routes)

    fun decodeRoutes(json: String): List<HistoricalRoute> =
        if (json.isBlank()) emptyList() else Json.decodeFromString(json)

    fun encodeRoute(route: HistoricalRoute?): String = Json.encodeToString(route)

    fun decodeRoute(json: String): HistoricalRoute? = Json.decodeFromString(json)

    fun encodePoints(points: List<Pair<Double, Double>>): String = Json.encodeToString(points)

    fun decodePoints(json: String): List<Pair<Double, Double>> =
        Json.decodeFromString<List<Pair<Double, Double>>>(json).also(::validatePoints)

    internal fun validatePoints(points: List<Pair<Double, Double>>) {
        require(points.size >= 2) { "路线经纬度至少需要两个点" }
        require(points.all { (lat, lon) -> lat in -90.0..90.0 && lon in -180.0..180.0 }) {
            "路线经纬度格式错误"
        }
    }
}
