package com.system.location.service.core.sensors

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Continuous, adjustable sensor cadence derived from motion + speed.
 *
 * The formula deliberately has no hard step-frequency table: walking/running cadence grows
 * smoothly with speed and is modulated by a bounded low-frequency wobble. Tuning lives on the
 * Java/config side; the Native layer only applies the resulting values.
 */
data class SensorTuning(
    val cadenceScale: Double = 1.0,
    val variability: Double = 0.15,
    val seed: Long = 0L,
) {
    init {
        require(cadenceScale.isFinite() && cadenceScale in 0.5..2.0)
        require(variability.isFinite() && variability in 0.0..0.5)
    }
}

object SensorCadence {
    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    fun base(mode: MotionMode, speedMps: Double): Double {
        if (!speedMps.isFinite() || speedMps < 0.3 || mode == MotionMode.STILL) return 0.0
        // Stride length grows smoothly from walking to running; cadence follows distance/time,
        // so a smooth route with acceleration naturally produces a continuous step rate.
        val stride = (0.55 + 0.18 * speedMps) +
            ((0.85 + 0.09 * speedMps) - (0.55 + 0.18 * speedMps)) *
            smoothstep(2.2, 3.2, speedMps)
        val raw = speedMps / stride * 60.0
        val fadeIn = smoothstep(0.0, 0.6, speedMps)
        val vehicleFade = 1.0 - smoothstep(5.0, 7.0, speedMps)
        return (raw * fadeIn * vehicleFade).coerceIn(0.0, 200.0)
    }

    /** Smoothly varying cadence; the same time/seed produces the same value deterministically. */
    fun cadence(
        mode: MotionMode,
        speedMps: Double,
        tuning: SensorTuning,
        atMillis: Long,
    ): Double {
        val base = base(mode, speedMps) * tuning.cadenceScale
        if (base <= 0.0) return 0.0
        val seconds = atMillis / 1_000.0
        val phase = (tuning.seed % 10_000L) / 10_000.0 * 2.0 * PI
        val wobble = 1.0 + tuning.variability * (
            0.32 * sin(2.0 * PI * 0.07 * seconds + phase) +
                0.12 * sin(2.0 * PI * 0.19 * seconds + phase * 1.7)
            )
        return (base * wobble).coerceIn(0.0, 220.0)
    }
}
