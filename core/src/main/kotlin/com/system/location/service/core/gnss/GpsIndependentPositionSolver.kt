package com.system.location.service.core.gnss

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** One code-phase range used by the independent receiver-side check. */
data class GpsRangeObservation(val satellite: EcefPosition, val meters: Double) {
    init {
        require(meters.isFinite() && meters > 0.0)
    }
}

data class GpsPositionSolution(
    val position: EcefPosition,
    val receiverClockBiasMeters: Double,
    val iterations: Int,
)

/**
 * Small independent least-squares GPS L1 position solver.
 *
 * It deliberately consumes only code ranges and satellite ECEF positions, not the target point or
 * Portal's synthetic receiver state. The device canary uses it to prove that the observations it
 * received actually converge to the configured target; production location injection does not call
 * this solver.
 */
object GpsIndependentPositionSolver {
    fun solve(
        observations: List<GpsRangeObservation>,
        initial: EcefPosition,
        maxIterations: Int = 100,
    ): GpsPositionSolution? {
        if (observations.size < 4 || maxIterations <= 0) return null
        var receiver = initial
        var clockMeters = 0.0
        repeat(maxIterations) { iteration ->
            val matrix = Array(observations.size) { row ->
                val observation = observations[row]
                val dx = receiver.xMeters - observation.satellite.xMeters
                val dy = receiver.yMeters - observation.satellite.yMeters
                val dz = receiver.zMeters - observation.satellite.zMeters
                val distance = hypot(hypot(dx, dy), dz)
                if (!distance.isFinite() || distance <= 0.0) return null
                doubleArrayOf(dx / distance, dy / distance, dz / distance, 1.0)
            }
            val residuals = DoubleArray(observations.size) { row ->
                val observation = observations[row]
                observation.meters - hypot(hypot(
                    receiver.xMeters - observation.satellite.xMeters,
                    receiver.yMeters - observation.satellite.yMeters,
                ), receiver.zMeters - observation.satellite.zMeters) - clockMeters
            }
            val step = leastSquaresQr(matrix, residuals) ?: return null
            receiver = EcefPosition(
                receiver.xMeters + step[0],
                receiver.yMeters + step[1],
                receiver.zMeters + step[2],
            )
            clockMeters += step[3]
            if (step.take(3).all { abs(it) < 1e-5 }) {
                return GpsPositionSolution(receiver, clockMeters, iteration + 1)
            }
        }
        return null
    }

    private fun leastSquaresQr(matrix: Array<DoubleArray>, values: DoubleArray): DoubleArray? {
        if (matrix.size < 4 || matrix.any { it.size != 4 }) return null
        val a = Array(matrix.size) { row -> matrix[row].copyOf() }
        val b = values.copyOf()
        for (column in 0..3) {
            val norm = sqrt((column until a.size).sumOf { a[it][column] * a[it][column] })
            if (!norm.isFinite() || norm <= 1e-10) return null
            val reflector = DoubleArray(a.size - column) { a[column + it][column] }
            reflector[0] += if (reflector[0] >= 0) norm else -norm
            val normSquared = reflector.sumOf { it * it }
            if (!normSquared.isFinite() || normSquared <= 1e-20) return null
            for (target in column..3) {
                val projection = 2 * reflector.indices.sumOf { reflector[it] * a[column + it][target] } / normSquared
                for (row in reflector.indices) a[column + row][target] -= projection * reflector[row]
            }
            val projection = 2 * reflector.indices.sumOf { reflector[it] * b[column + it] } / normSquared
            for (row in reflector.indices) b[column + row] -= projection * reflector[row]
        }
        val solution = DoubleArray(4)
        for (row in 3 downTo 0) {
            val diagonal = a[row][row]
            if (!diagonal.isFinite() || abs(diagonal) <= 1e-12) return null
            solution[row] = (b[row] - (row + 1..3).sumOf { a[row][it] * solution[it] }) / diagonal
            if (!solution[row].isFinite()) return null
        }
        return solution
    }
}
