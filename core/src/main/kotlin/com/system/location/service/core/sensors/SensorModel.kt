package com.system.location.service.core.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

enum class MotionMode { STILL, WALK, RUN, VEHICLE }

enum class ActivityKind { STILL, WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, UNKNOWN }

data class MotionState(
    val mode: MotionMode,
    val speedMps: Double,
    val bearingDeg: Double,
    val altitudeMeters: Double,
) {
    init {
        require(speedMps.isFinite() && speedMps >= 0.0) { "speedMps must be non-negative" }
        require(bearingDeg.isFinite() && bearingDeg in 0.0..360.0) { "bearingDeg out of range" }
        require(altitudeMeters.isFinite() && altitudeMeters in -500.0..20_000.0) { "altitudeMeters out of range" }
    }
}

data class SensorFrame(
    val accelerometer: FloatArray,
    val gyroscope: FloatArray,
    val magnetometer: FloatArray,
    val barometerHpa: Float,
    val stepCount: Long,
    val stepCadence: Int,
    val activity: ActivityKind,
) {
    init {
        require(accelerometer.size == 3 && gyroscope.size == 3 && magnetometer.size == 3) {
            "sensor vectors must have three axes"
        }
        require(accelerometer.all(Float::isFinite) && gyroscope.all(Float::isFinite) &&
            magnetometer.all(Float::isFinite)) { "sensor vectors must be finite" }
        require(barometerHpa.isFinite() && barometerHpa in 300f..1_100f) { "barometer out of range" }
        require(stepCount >= 0L) { "stepCount must be non-negative" }
        require(stepCadence >= 0) { "stepCadence must be non-negative" }
    }
}

/** Motion state -> sensor values (P4-05); the native bridge will consume this frame later (P4-04). */
interface SensorModel {
    fun frame(state: MotionState, atMillis: Long): SensorFrame
}

/**
 * Deterministic synthetic sensor model.
 *
 * The same seed and timestamp sequence always produce the same frames; the model never uses a
 * global RNG. Step counting keeps the fractional remainder so a 1 Hz caller still advances steps.
 */
class SyntheticSensorModel(seed: Long = 0L) : SensorModel {
    private var random = SplitMix64(seed)
    private val seed = seed
    private var lastMillis: Long? = null
    private var fractionalSteps = 0.0
    private var stepCount = 0L

    fun reset() {
        random = SplitMix64(seed)
        lastMillis = null
        fractionalSteps = 0.0
        stepCount = 0L
    }

    override fun frame(state: MotionState, atMillis: Long): SensorFrame {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        val previous = lastMillis
        lastMillis = atMillis
        val dt = if (previous == null) 0.0 else ((atMillis - previous) / 1_000.0).coerceIn(0.0, 5.0)
        val t = atMillis / 1_000.0

        val cadence = when (state.mode) {
            MotionMode.STILL, MotionMode.VEHICLE -> 0
            MotionMode.WALK -> 105
            MotionMode.RUN -> 170
        }
        if (cadence > 0 && dt > 0.0) {
            fractionalSteps += cadence / 60.0 * dt
            val whole = floor(fractionalSteps).toLong()
            if (whole > 0) {
                stepCount += whole
                fractionalSteps -= whole
            }
        }

        val noise = { scale: Double -> (random.nextGaussian() * scale).toFloat() }
        val (ax, az) = when (state.mode) {
            MotionMode.STILL -> 0.0 to 9.81 + random.nextGaussian() * 0.02
            MotionMode.WALK -> 0.45 * sin(2 * PI * 1.6 * t + 0.5) to 9.81 + 1.4 * sin(2 * PI * 1.6 * t)
            MotionMode.RUN -> 1.2 * sin(2 * PI * 2.7 * t + 0.4) to 9.81 + 2.8 * sin(2 * PI * 2.7 * t)
            MotionMode.VEHICLE -> 0.2 * sin(2 * PI * 8.0 * t) to 9.81 + 0.15 * sin(2 * PI * 8.0 * t)
        }
        val gyroZ = when (state.mode) {
            MotionMode.STILL -> 0.005
            MotionMode.WALK -> 0.08 * sin(2 * PI * 1.6 * t)
            MotionMode.RUN -> 0.22 * sin(2 * PI * 2.7 * t)
            MotionMode.VEHICLE -> 0.012 * sin(2 * PI * 0.5 * t)
        }
        val bearingRad = Math.toRadians(state.bearingDeg)
        val magnetometerX = cos(bearingRad) * 25.0 + random.nextGaussian() * 0.3
        val magnetometerY = sin(bearingRad) * 25.0 + random.nextGaussian() * 0.3
        val magnetometerZ = -40.0 + random.nextGaussian() * 0.2
        val pressure = 1013.25 * (1.0 - 2.25577e-5 * state.altitudeMeters).pow(5.25588)

        return SensorFrame(
            accelerometer = floatArrayOf(ax.toFloat() + noise(0.02), noise(0.02), az.toFloat() + noise(0.02)),
            gyroscope = floatArrayOf(noise(0.003), noise(0.003), gyroZ.toFloat() + noise(0.003)),
            magnetometer = floatArrayOf(magnetometerX.toFloat(), magnetometerY.toFloat(), magnetometerZ.toFloat()),
            barometerHpa = (pressure + random.nextGaussian() * 0.02).toFloat(),
            stepCount = stepCount,
            stepCadence = cadence,
            activity = state.mode.toActivity(),
        )
    }

    private fun MotionMode.toActivity(): ActivityKind = when (this) {
        MotionMode.STILL -> ActivityKind.STILL
        MotionMode.WALK -> ActivityKind.WALKING
        MotionMode.RUN -> ActivityKind.RUNNING
        MotionMode.VEHICLE -> ActivityKind.IN_VEHICLE
    }

    private class SplitMix64(seed: Long) {
        private var state = seed
        private var spare: Double? = null

        fun nextGaussian(): Double {
            spare?.let { spare = null; return it }
            var u1: Double
            do {
                u1 = nextDouble()
            } while (u1 <= 0.0)
            val u2 = nextDouble()
            val radius = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1))
            val theta = 2.0 * PI * u2
            spare = radius * sin(theta)
            return radius * cos(theta)
        }

        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
