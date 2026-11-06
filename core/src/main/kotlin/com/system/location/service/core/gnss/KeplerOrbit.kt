package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class KeplerElements(
    val semiMajorAxisMeters: Double,
    val eccentricity: Double,
    val inclinationRad: Double,
    val raanRad: Double,
    val argumentOfPerigeeRad: Double,
    val meanAnomalyRad: Double,
    val epochSeconds: Double,
    val meanMotionRadPerSec: Double? = null,
) {
    init {
        require(semiMajorAxisMeters in MIN_SEMI_MAJOR..MAX_SEMI_MAJOR) { "semiMajorAxis out of range" }
        require(eccentricity in 0.0..0.9) { "eccentricity out of range" }
        require(inclinationRad.isFinite() && raanRad.isFinite() && argumentOfPerigeeRad.isFinite()) {
            "angles must be finite"
        }
        require(meanAnomalyRad.isFinite() && epochSeconds.isFinite()) { "mean anomaly/epoch must be finite" }
        meanMotionRadPerSec?.let { require(it > 0.0) { "meanMotion must be positive" } }
    }

    companion object {
        const val MIN_SEMI_MAJOR = 6_400_000.0
        const val MAX_SEMI_MAJOR = 60_000_000.0
    }
}

data class EciPosition(val x: Double, val y: Double, val z: Double) {
    val radiusMeters: Double get() = sqrt(x * x + y * y + z * z)
}

/** P3-05 Kepler/Doppler spike: deterministic two-body propagation, no perturbations. */
class KeplerOrbit(val elements: KeplerElements) {
    val meanMotionRadPerSec: Double =
        elements.meanMotionRadPerSec ?: sqrt(EARTH_MU / (elements.semiMajorAxisMeters * elements.semiMajorAxisMeters * elements.semiMajorAxisMeters))

    fun positionAt(seconds: Double): EciPosition {
        require(seconds.isFinite()) { "seconds must be finite" }
        val elapsed = seconds - elements.epochSeconds
        val meanAnomaly = normalize(elements.meanAnomalyRad + meanMotionRadPerSec * elapsed)
        var eccentricAnomaly = meanAnomaly
        repeat(12) {
            eccentricAnomaly -= (eccentricAnomaly - elements.eccentricity * sin(eccentricAnomaly) - meanAnomaly) /
                (1.0 - elements.eccentricity * cos(eccentricAnomaly))
        }
        val a = elements.semiMajorAxisMeters
        val xv = a * (cos(eccentricAnomaly) - elements.eccentricity)
        val yv = a * sqrt(1.0 - elements.eccentricity * elements.eccentricity) * sin(eccentricAnomaly)
        val radius = sqrt(xv * xv + yv * yv)
        val trueAnomaly = atan2(yv, xv)
        val argumentOfLatitude = trueAnomaly + elements.argumentOfPerigeeRad
        val cosU = cos(argumentOfLatitude)
        val sinU = sin(argumentOfLatitude)
        val cosO = cos(elements.raanRad)
        val sinO = sin(elements.raanRad)
        val cosI = cos(elements.inclinationRad)
        val sinI = sin(elements.inclinationRad)
        return EciPosition(
            x = radius * (cosO * cosU - sinO * sinU * cosI),
            y = radius * (sinO * cosU + cosO * sinU * cosI),
            z = radius * (sinU * sinI),
        )
    }

    fun orbitalPeriodSeconds(): Double = 2.0 * PI / meanMotionRadPerSec

    private fun normalize(angle: Double): Double {
        val twoPi = 2.0 * PI
        return ((angle % twoPi) + twoPi) % twoPi
    }

    companion object {
        const val EARTH_MU = 3.986004418e14
        const val SPEED_OF_LIGHT_MPS = 299_792_458.0
    }
}

object DopplerShift {
    /** Positive range rate (receding) lowers the observed frequency. */
    fun observedHz(carrierHz: Double, rangeRateMps: Double): Double {
        require(carrierHz > 0.0) { "carrierHz must be positive" }
        require(rangeRateMps.isFinite()) { "rangeRateMps must be finite" }
        return carrierHz * (1.0 - rangeRateMps / KeplerOrbit.SPEED_OF_LIGHT_MPS)
    }
}
