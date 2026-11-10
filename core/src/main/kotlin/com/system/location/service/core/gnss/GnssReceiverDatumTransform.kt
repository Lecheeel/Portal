package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable
import kotlin.math.*

data class GnssDatumReceiver(val position: EcefPosition, val orientation: GeodeticOrientation)

/**
 * An independently established WGS 84 ECEF -> target ECEF similarity transform, evaluated
 * over the supplied same-boot elapsed interval [from, until). Coefficients are a full proper
 * rotation, positive scale and translation, not small-angle Helmert parameters with an
 * ambiguous position-vector/coordinate-frame convention. Target ellipsoid parameters are
 * explicit. No identity transform, frame realization, epoch, or applicability is inferred.
 */
@Serializable
data class GnssReceiverDatumTransform(
    val evidenceId: String,
    val bootCount: Int,
    val validFromElapsedNanos: Long,
    val validUntilElapsedNanos: Long,
    val rotation: List<Double>,
    val translationMeters: List<Double>,
    val scale: Double,
    val targetSemiMajorAxisMeters: Double,
    val targetInverseFlattening: Double,
) {
    init {
        require(evidenceId.isNotBlank() && evidenceId.length <= 256)
        require(bootCount >= 0 && validFromElapsedNanos >= 0 && validUntilElapsedNanos > validFromElapsedNanos)
        require(rotation.size == 9 && rotation.all(Double::isFinite))
        require(translationMeters.size == 3 && translationMeters.all(Double::isFinite))
        require(scale.isFinite() && scale > 0.0)
        require(targetSemiMajorAxisMeters.isFinite() && targetSemiMajorAxisMeters > 0.0)
        require(targetInverseFlattening.isFinite() && targetInverseFlattening > 1.0)
        fun dot(a: Int, b: Int) = (0..2).sumOf { rotation[3 * a + it] * rotation[3 * b + it] }
        require((0..2).all { abs(dot(it, it) - 1.0) <= 1e-12 })
        require(abs(dot(0, 1)) <= 1e-12 && abs(dot(0, 2)) <= 1e-12 && abs(dot(1, 2)) <= 1e-12)
        val r = rotation
        val determinant = r[0] * (r[4] * r[8] - r[5] * r[7]) -
            r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6])
        require(abs(determinant - 1.0) <= 1e-12)
    }

    fun receiver(observer: Wgs84, altitudeMeters: Double, elapsedNanos: Long?, currentBootCount: Int?): GnssDatumReceiver? {
        if (currentBootCount != bootCount || elapsedNanos == null ||
            elapsedNanos < validFromElapsedNanos || elapsedNanos >= validUntilElapsedNanos || !altitudeMeters.isFinite())
            return null
        return runCatching {
            val wgs = GnssObserverGeometry.ecef(observer, altitudeMeters)
            val input = listOf(wgs.xMeters, wgs.yMeters, wgs.zMeters)
            val output = (0..2).map { row ->
                scale * (0..2).sumOf { column -> rotation[row * 3 + column] * input[column] } + translationMeters[row]
            }
            val position = EcefPosition(output[0], output[1], output[2])
            val orientation = orientation(position) ?: return null
            GnssDatumReceiver(position, orientation)
        }.getOrNull()
    }

    private fun orientation(position: EcefPosition): GeodeticOrientation? {
        val p = hypot(position.xMeters, position.yMeters)
        val z = position.zMeters
        val f = 1.0 / targetInverseFlattening
        val e2 = f * (2.0 - f)
        // A pole has no uniquely defined azimuth reference. Deep interior points may have
        // multiple ellipsoid normals: do not choose an arbitrary branch for a receiver.
        if (p == 0.0 || hypot(p, z) <= targetSemiMajorAxisMeters * e2) return null
        var latitude = atan2(z, p * (1.0 - e2))
        repeat(64) {
            val sine = sin(latitude)
            val n = targetSemiMajorAxisMeters / sqrt(1.0 - e2 * sine * sine)
            val next = atan2(z + e2 * n * sine, p)
            if (abs(next - latitude) <= 1e-14) {
                val normal = targetSemiMajorAxisMeters / sqrt(1.0 - e2 * sin(next).pow(2))
                val residual = p * sin(next) - z * cos(next) - e2 * normal * sin(next) * cos(next)
                if (abs(residual) > hypot(p, z) * 1e-12) return null
                return GeodeticOrientation(Math.toDegrees(next), Math.toDegrees(atan2(position.yMeters, position.xMeters)))
            }
            latitude = next
        }
        return null
    }
}
