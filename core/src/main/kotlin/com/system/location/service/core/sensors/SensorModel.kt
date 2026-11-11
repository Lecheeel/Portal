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
    val forwardAccelerationMps2: Double = 0.0,
    val turnRateRadPerSec: Double = 0.0,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
) {
    init {
        require(speedMps.isFinite() && speedMps >= 0.0) { "speedMps must be non-negative" }
        require(bearingDeg.isFinite() && bearingDeg in 0.0..360.0) { "bearingDeg out of range" }
        require(altitudeMeters.isFinite() && altitudeMeters in -500.0..20_000.0) { "altitudeMeters out of range" }
        require(listOf(forwardAccelerationMps2, turnRateRadPerSec, rollDeg, pitchDeg).all(Double::isFinite))
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
    val gravity: FloatArray = floatArrayOf(0f, 0f, 9.81f),
    val linearAcceleration: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Android rotation-vector order: x, y, z, w. */
    val rotationVector: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
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
class SyntheticSensorModel(
    seed: Long = 0L,
    var tuning: SensorTuning = SensorTuning(seed = seed),
) : SensorModel {
    private val seed = seed
    private var lastMillis: Long? = null
    private var lastFrame: SensorFrame? = null
    private var lastCadence = 0.0
    private var fractionalSteps = 0.0
    private var stepCount = 0L
    private var accelPhase = 0.0
    private var gyroPhase = 0.0

    fun reset(startStepCount: Long = 0L) {
        lastMillis = null
        lastFrame = null
        lastCadence = 0.0
        fractionalSteps = 0.0
        stepCount = startStepCount.coerceAtLeast(0L)
        accelPhase = 0.0
        gyroPhase = 0.0
    }

    override fun frame(state: MotionState, atMillis: Long): SensorFrame {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        val previous = lastMillis
        if (previous != null && atMillis <= previous) return lastFrame!!
        lastMillis = atMillis
        val dt = if (previous == null) 0.0 else ((atMillis - previous) / 1_000.0).coerceIn(0.0, 5.0)
        val t = atMillis / 1_000.0

        val cadence = SensorCadence.cadence(state.mode, state.speedMps, tuning, atMillis)
        if (dt > 0.0) {
            fractionalSteps += (cadence + lastCadence) / 120.0 * dt
            val whole = floor(fractionalSteps).toLong()
            if (whole > 0) {
                stepCount += whole
                fractionalSteps -= whole
            }
        }
        lastCadence = cadence

        val stepHz = when (state.mode) {
            MotionMode.STILL -> 0.0
            MotionMode.VEHICLE -> 8.0
            MotionMode.WALK, MotionMode.RUN -> cadence / 60.0
        }
        val gyroHz = when (state.mode) {
            MotionMode.STILL -> 0.0
            MotionMode.VEHICLE -> 0.5
            MotionMode.WALK, MotionMode.RUN -> stepHz
        }
        accelPhase = (accelPhase + 2.0 * PI * stepHz * dt) % (2.0 * PI)
        gyroPhase = (gyroPhase + 2.0 * PI * gyroHz * dt) % (2.0 * PI)
        val swing = sin(accelPhase)
        val gyroSwing = sin(gyroPhase)
        val amplitude = 1.0 + tuning.variability * 0.15 * sin(2.0 * PI * 0.07 * t)

        // Noise depends on the requested time, not on how many listeners sampled it.
        val random = SplitMix64(seed xor atMillis)
        val noise = { scale: Double -> (random.nextGaussian() * scale).toFloat() }
        val (ax, az) = when (state.mode) {
            MotionMode.STILL -> 0.0 to 9.81 + random.nextGaussian() * 0.02
            MotionMode.WALK -> 0.45 * swing * amplitude to 9.81 + 1.4 * swing * amplitude
            MotionMode.RUN -> 1.2 * swing * amplitude to 9.81 + 2.8 * swing * amplitude
            MotionMode.VEHICLE -> 0.2 * swing * amplitude to 9.81 + 0.15 * swing * amplitude
        }
        val gyroZ = when (state.mode) {
            MotionMode.STILL -> 0.0
            MotionMode.WALK -> 0.08 * gyroSwing * amplitude
            MotionMode.RUN -> 0.22 * gyroSwing * amplitude
            MotionMode.VEHICLE -> 0.012 * gyroSwing
        }
        val roll = Math.toRadians(state.rollDeg)
        val pitch = Math.toRadians(state.pitchDeg)
        val yaw = -Math.toRadians(state.bearingDeg)
        val cr = cos(roll); val sr = sin(roll)
        val cp = cos(pitch); val sp = sin(pitch)
        val cy = cos(yaw); val sy = sin(yaw)
        // R^T maps the local world into the device axes. No gravity is integrated as motion.
        fun body(x: Double, y: Double, z: Double) = floatArrayOf(
            (cy * cp * x + sy * cp * y - sp * z).toFloat(),
            ((cy * sp * sr - sy * cr) * x + (sy * sp * sr + cy * cr) * y + cp * sr * z).toFloat(),
            ((cy * sp * cr + sy * sr) * x + (sy * sp * cr - cy * sr) * y + cp * cr * z).toFloat(),
        )
        val gravity = body(0.0, 0.0, 9.81)
        val lateral = ax + state.speedMps * state.turnRateRadPerSec
        val forward = state.forwardAccelerationMps2
        val linear = body(cy * lateral - sy * forward, sy * lateral + cy * forward, az - 9.81)
            .also { v -> for (i in 0..2) v[i] += noise(0.02) }
        val angular = body(0.0, 0.0, -state.turnRateRadPerSec).also {
            it[0] += noise(0.003); it[1] += noise(0.003); it[2] += gyroZ.toFloat() + noise(0.003)
        }
        val magnetic = body(0.0, 25.0, -40.0).also { v ->
            for (i in 0..2) v[i] += noise(0.2)
        }
        val hr = roll / 2; val hp = pitch / 2; val hy = yaw / 2
        val rotation = floatArrayOf(
            (sin(hr) * cos(hp) * cos(hy) - cos(hr) * sin(hp) * sin(hy)).toFloat(),
            (cos(hr) * sin(hp) * cos(hy) + sin(hr) * cos(hp) * sin(hy)).toFloat(),
            (cos(hr) * cos(hp) * sin(hy) - sin(hr) * sin(hp) * cos(hy)).toFloat(),
            (cos(hr) * cos(hp) * cos(hy) + sin(hr) * sin(hp) * sin(hy)).toFloat(),
        )
        val pressure = 1013.25 * (1.0 - 2.25577e-5 * state.altitudeMeters).pow(5.25588)

        return SensorFrame(
            accelerometer = FloatArray(3) { gravity[it] + linear[it] },
            gyroscope = angular,
            magnetometer = magnetic,
            barometerHpa = (pressure + random.nextGaussian() * 0.02).toFloat(),
            stepCount = stepCount,
            stepCadence = kotlin.math.round(cadence).toInt(),
            activity = state.mode.toActivity(),
            gravity = gravity,
            linearAcceleration = linear,
            rotationVector = rotation,
        ).also { lastFrame = it }
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
