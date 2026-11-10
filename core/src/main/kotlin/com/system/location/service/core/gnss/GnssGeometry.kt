package com.system.location.service.core.gnss

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
data class GnssDop(val hdop: Double, val vdop: Double, val pdop: Double, val tdop: Double, val gdop: Double) {
    init { require(listOf(hdop, vdop, pdop, tdop, gdop).all { it.isFinite() && it > 0 }) }
}

data class GnssGeometryResult(val dop: GnssDop?, val reason: String? = null, val conditionNumber: Double? = null)

/**
 * Equal-variance geometric DOP (W = I), not a CN0-derived accuracy estimate.
 * H is in local ENU, so Qee/Qnn/Quu directly yield HDOP/VDOP.
 * https://gssc.esa.int/navipedia/index.php/Positioning_Error
 *
 * Each constellation gets a separate receiver clock column: absent measured inter-system time
 * offsets, sharing one clock would overstate the information provided by mixed constellations.
 * Q = R^-1 R^-T is computed by Householder QR instead of inverting H^T H (which squares conditioning).
 */
object GnssGeometry {
    fun solve(satellites: List<GnssSatellite>): GnssGeometryResult {
        val used = satellites.filter { it.usedInFix && it.elevationDeg >= 0f }
        if (used.map { it.constellation to it.svid }.distinct().size != used.size) {
            return GnssGeometryResult(null, "duplicate satellite identity")
        }
        val systems = used.map { it.constellation }.distinct().sortedBy { it.ordinal }
        val columns = 3 + systems.size
        if (used.size < maxOf(4, columns)) return GnssGeometryResult(null, "insufficient observations for position and receiver clocks")
        val matrix = Array(used.size) { index ->
            val satellite = used[index]
            val elevation = Math.toRadians(satellite.elevationDeg.toDouble())
            val azimuth = Math.toRadians(satellite.azimuthDeg.toDouble())
            DoubleArray(columns).apply {
                this[0] = cos(elevation) * sin(azimuth)
                this[1] = cos(elevation) * cos(azimuth)
                this[2] = sin(elevation)
                this[3 + systems.indexOf(satellite.constellation)] = 1.0
            }
        }
        for (column in 0 until columns) {
            val norm = sqrt((column until matrix.size).sumOf { matrix[it][column] * matrix[it][column] })
            if (!norm.isFinite() || norm < 1e-10) return GnssGeometryResult(null, "rank deficient satellite geometry")
            val reflector = DoubleArray(matrix.size - column) { matrix[column + it][column] }
            reflector[0] += if (reflector[0] >= 0) norm else -norm
            val normSquared = reflector.sumOf { it * it }
            for (target in column until columns) {
                val projection = 2 * reflector.indices.sumOf { reflector[it] * matrix[column + it][target] } / normSquared
                for (row in reflector.indices) matrix[column + row][target] -= projection * reflector[row]
            }
        }
        val r = Array(columns) { row -> DoubleArray(columns) { column -> if (column >= row) matrix[row][column] else 0.0 } }
        val inverse = Array(columns) { DoubleArray(columns) }
        for (column in 0 until columns) {
            for (row in columns - 1 downTo 0) {
                var value = if (row == column) 1.0 else 0.0
                for (other in row + 1 until columns) value -= r[row][other] * inverse[other][column]
                inverse[row][column] = value / r[row][row]
            }
        }
        fun infinityNorm(values: Array<DoubleArray>) = values.maxOf { row -> row.sumOf { abs(it) } }
        val condition = infinityNorm(r) * infinityNorm(inverse)
        // Accuracy worse than roughly 1e-6 relative is rejected rather than capped to a plausible DOP.
        if (!condition.isFinite() || condition > 1e6) return GnssGeometryResult(null, "ill-conditioned satellite geometry", condition)
        val diagonal = DoubleArray(columns) { row -> inverse[row].sumOf { it * it } }
        val horizontal = diagonal[0] + diagonal[1]
        val vertical = diagonal[2]
        val time = diagonal[3] // Reference constellation clock; additional clocks are nuisance states.
        return GnssGeometryResult(GnssDop(sqrt(horizontal), sqrt(vertical), sqrt(horizontal + vertical),
            sqrt(time), sqrt(horizontal + vertical + time)), conditionNumber = condition)
    }
}
