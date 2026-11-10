package com.system.location.service.core.gnss

import kotlin.math.abs
import kotlin.math.hypot

/** Realization is supplied by the navigation-data source; it is never inferred from a slot. */
enum class GlonassReferenceFrame { PZ_90_02, PZ_90_11 }

/** Components in one explicitly identified PZ-90 ECEF realization, in SI units. */
data class GlonassCartesianVector(val x: Double, val y: Double, val z: Double) {
    init { require(listOf(x, y, z).all(Double::isFinite)) }
    internal fun within(limit: Double) = abs(x) <= limit && abs(y) <= limit && abs(z) <= limit
    internal fun norm() = hypot(hypot(x, y), z)
}

/**
 * GLONASS FDMA immediate orbital data at tb (ICD 5.1 Table 4.5), after bit validation.
 * Acceleration is the broadcast luni-solar perturbation, NOT the total acceleration.
 * No calendar, leap-second, GPS/UTC association, health, clock or service validity is implied.
 * A caller must independently establish the requested SI interval from tb before propagation.
 */
data class GlonassBroadcastStateVector(
    val slot: Int,
    val referenceFrame: GlonassReferenceFrame,
    val positionMeters: GlonassCartesianVector,
    val velocityMetersPerSecond: GlonassCartesianVector,
    val luniSolarAccelerationMetersPerSecondSquared: GlonassCartesianVector,
) {
    init {
        // n is a five-bit slot field. Do not hard-code a historical operational constellation size.
        require(slot in 1..31)
        // Sign-magnitude bit capacities, not rounded nominal constellation ranges.
        require(positionMeters.within(67_108_863 * (1000.0 / 2048)))
        require(positionMeters.norm() > 6_378_136.0)
        require(velocityMetersPerSecond.within(8_388_607 * (1000.0 / 1_048_576)))
        require(luniSolarAccelerationMetersPerSecondSquared.within(15 * (1000.0 / 1_073_741_824)))
    }
}
