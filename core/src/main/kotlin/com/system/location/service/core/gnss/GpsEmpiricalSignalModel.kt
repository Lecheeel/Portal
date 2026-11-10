package com.system.location.service.core.gnss

import kotlin.math.abs
import kotlinx.serialization.Serializable

@Serializable
data class GpsEmpiricalSignal(
    val cn0DbHz: Double,
    /** Captured usage frequency, not a prediction of live receiver lock or future health. */
    val usedFraction: Double,
    val hasAlmanac: Boolean,
    val carrierHz: Float?,
) {
    init {
        require(cn0DbHz.isFinite() && cn0DbHz in 0.0..63.0)
        require(usedFraction.isFinite() && usedFraction in 0.0..1.0)
        require(carrierHz == null || carrierHz.isFinite() && carrierHz > 0f &&
            abs(carrierHz.toDouble() - GPS_L1_HZ) <= GPS_CARRIER_TOLERANCE_HZ)
    }
}

@Serializable
data class GpsEmpiricalSignalKnot(val elevation: Double, val signal: GpsEmpiricalSignal) {
    init { require(elevation.isFinite() && elevation in 0.0..90.0) }
}

/** Compact sufficient statistics for exactly the same empirical interpolator across IPC. */
@Serializable
data class GpsSignalCalibration(val knots: List<GpsEmpiricalSignalKnot>) {
    init {
        require(knots.size <= GnssCapture.MAX_FRAMES * 512)
        require(knots.zipWithNext().all { (a, b) -> a.elevation < b.elevation })
    }
}

private const val GPS_L1_HZ = 1_575_420_000.0
private const val GPS_CARRIER_TOLERANCE_HZ = 100_000.0

/**
 * Piecewise-linear conditional empirical means of GPS status CN0/usage versus elevation.
 * Uses this capture's environment only; it does not infer target-site obstructions or RF power.
 * No extrapolation outside observed elevations, no synthetic sine curve or signal-strength floor.
 * Known non-L1 carriers are excluded; unknown carriers stay unknown in the output.
 */
class GpsEmpiricalSignalModel(capture: GnssCapture) {
    internal val calibration: GpsSignalCalibration =
        if (capture.frames.none { it.signalObservations.isNotEmpty() } && capture.gpsSignalCalibration != null)
            capture.gpsSignalCalibration else fit(capture)
    private val knots = calibration.knots

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

    private fun fit(capture: GnssCapture): GpsSignalCalibration = GpsSignalCalibration(capture.frames.flatMap { frame ->
        // Spacecraft projection may prefer a used L5 signal. It cannot replace captured L1 RF evidence.
        if (frame.signalObservations.isNotEmpty()) frame.signalObservations.map { it.satellite } else frame.satellites
    }.filter {
        it.constellation == Constellation.GPS && it.elevationDeg >= 0f && it.azimuthDeg < 360f &&
            (it.carrierHz == null || abs(it.carrierHz.toDouble() - GPS_L1_HZ) <= GPS_CARRIER_TOLERANCE_HZ)
    }.groupBy { it.elevationDeg }.toSortedMap().map { (elevation, samples) ->
        GpsEmpiricalSignalKnot(elevation.toDouble(), GpsEmpiricalSignal(samples.map { it.cn0DbHz.toDouble() }.average(),
            samples.count { it.usedInFix }.toDouble() / samples.size,
            samples.count { it.hasAlmanac } * 2 >= samples.size,
            samples.map { it.carrierHz }.distinct().singleOrNull()))
    })
}
