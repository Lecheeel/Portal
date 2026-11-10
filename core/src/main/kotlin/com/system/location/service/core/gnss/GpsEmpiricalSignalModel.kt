package com.system.location.service.core.gnss

import kotlin.math.abs

data class GpsEmpiricalSignal(
    val cn0DbHz: Double,
    /** Captured usage frequency, not a prediction of live receiver lock or future health. */
    val usedFraction: Double,
    val hasAlmanac: Boolean,
    val carrierHz: Float?,
)

/**
 * Piecewise-linear conditional empirical means of GPS status CN0/usage versus elevation.
 * Uses this capture's environment only; it does not infer target-site obstructions or RF power.
 * No extrapolation outside observed elevations, no synthetic sine curve or signal-strength floor.
 * Known non-L1 carriers are excluded; unknown carriers stay unknown in the output.
 */
class GpsEmpiricalSignalModel(capture: GnssCapture) {
    private data class Knot(val elevation: Double, val signal: GpsEmpiricalSignal)
    private val knots = capture.frames.flatMap { it.satellites }.filter {
        it.constellation == Constellation.GPS && it.elevationDeg >= 0f &&
            (it.carrierHz == null || abs(it.carrierHz.toDouble() - L1_HZ) <= CARRIER_TOLERANCE_HZ)
    }.groupBy { it.elevationDeg }.toSortedMap().map { (elevation, samples) ->
        Knot(elevation.toDouble(), GpsEmpiricalSignal(samples.map { it.cn0DbHz.toDouble() }.average(),
            samples.count { it.usedInFix }.toDouble() / samples.size,
            samples.count { it.hasAlmanac } * 2 >= samples.size,
            samples.map { it.carrierHz }.distinct().singleOrNull()))
    }

    fun at(elevationDegrees: Double): GpsEmpiricalSignal? {
        require(elevationDegrees.isFinite() && elevationDegrees in 0.0..90.0)
        if (knots.isEmpty() || elevationDegrees < knots.first().elevation || elevationDegrees > knots.last().elevation)
            return null
        val rightIndex = knots.indexOfFirst { it.elevation >= elevationDegrees }
        val right = knots[rightIndex]
        if (right.elevation == elevationDegrees) return right.signal
        val left = knots[rightIndex - 1]
        val fraction = (elevationDegrees - left.elevation) / (right.elevation - left.elevation)
        fun mix(a: Double, b: Double) = a + (b - a) * fraction
        return GpsEmpiricalSignal(mix(left.signal.cn0DbHz, right.signal.cn0DbHz),
            mix(left.signal.usedFraction, right.signal.usedFraction),
            if (fraction < 0.5) left.signal.hasAlmanac else right.signal.hasAlmanac,
            left.signal.carrierHz.takeIf { it == right.signal.carrierHz })
    }

    private companion object {
        const val L1_HZ = 1_575_420_000.0
        const val CARRIER_TOLERANCE_HZ = 100_000.0
    }
}
