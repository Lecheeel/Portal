package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84

/** Stable observer identity, independent of movement, provider latency and physical noise. */
sealed interface GnssFrameRoute {
    data object Global : GnssFrameRoute
    data class FixedPoint(val point: Wgs84) : GnssFrameRoute
    data class Scenario(val id: String) : GnssFrameRoute {
        init { require(id.isNotBlank()) }
    }

    companion object {
        fun forObserver(scenarioId: String? = null, point: Wgs84? = null): GnssFrameRoute {
            require(scenarioId == null || point == null)
            return point?.let(::FixedPoint) ?: scenarioId?.let(::Scenario) ?: Global
        }
    }
}
