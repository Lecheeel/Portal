package com.system.location.service.ui.mock

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*

/** Route persistence uses generated serializers, including Kotlin's built-in Pair serializer. */
object RouteJson {
    fun encodeRoutes(routes: List<HistoricalRoute>): String = envelope("routes", Json.encodeToJsonElement(routes))

    fun decodeRoutes(json: String): List<HistoricalRoute> =
        if (json.isBlank()) emptyList() else Json.decodeFromJsonElement(payload(json, "routes"))

    fun encodeRoute(route: HistoricalRoute?): String = envelope("route", Json.encodeToJsonElement(route))

    fun decodeRoute(json: String): HistoricalRoute? = Json.decodeFromJsonElement(payload(json, "route"))

    private fun envelope(key: String, value: JsonElement) = buildJsonObject {
        put("schemaVersion", 1)
        put(key, value)
    }.toString()

    private fun payload(text: String, key: String): JsonElement {
        val root = Json.parseToJsonElement(text)
        if (root is JsonObject && "schemaVersion" in root) {
            require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported route schema" }
            return root[key] ?: error("Missing $key")
        }
        return root // Existing unversioned route records are migrated on the next write.
    }

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
