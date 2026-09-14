package com.system.location.service.core.scenario

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable
import java.util.Collections

@Serializable enum class RouteMode { ONCE, LOOP, PING_PONG }

@Serializable data class Route(val id: String, val name: String, val points: List<Wgs84>) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(points.size in 2..100_000) { "Route requires 2–100000 points" }
    }
    fun frozen(): Route = copy(points = Collections.unmodifiableList(ArrayList(points)))
}

@Serializable data class MovementProfile(val speedMps: Double = 3.05, val altitudeMeters: Double = 80.0,
    val accuracyMeters: Float = 25f, val intervalMs: Long = 500) {
    init {
        require(speedMps.isFinite() && speedMps in 0.0..1000.0)
        require(altitudeMeters.isFinite())
        require(accuracyMeters.isFinite() && accuracyMeters > 0)
        require(intervalMs in 50..1000)
    }
}

@Serializable data class Scenario(val id: String, val name: String, val point: Wgs84? = null,
    val route: Route? = null, val mode: RouteMode = RouteMode.ONCE,
    val profile: MovementProfile = MovementProfile()) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require((point == null) != (route == null)) { "Choose a point or a route" }
    }
    fun frozen(): Scenario = copy(route = route?.frozen())
}
