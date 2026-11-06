package com.system.location.service.core

import com.system.location.service.core.sensors.ActivityKind
import com.system.location.service.core.sensors.MotionMode
import com.system.location.service.core.sensors.MotionState
import com.system.location.service.core.sensors.SensorFrame
import com.system.location.service.core.sensors.SyntheticSensorModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class SensorModelTest {
    private val state = MotionState(MotionMode.STILL, 0.0, 90.0, 80.0)

    @Test
    fun stillMotionHasGravityAndNoSteps() {
        val model = SyntheticSensorModel(seed = 1)
        val frames = (1..10).map { model.frame(state, it * 1_000L) }
        frames.forEach { frame ->
            val magnitude = magnitude(frame.accelerometer)
            assertTrue("magnitude=$magnitude", abs(magnitude - 9.81) < 1.0)
            assertEquals(0L, frame.stepCount)
            assertEquals(0, frame.stepCadence)
            assertEquals(ActivityKind.STILL, frame.activity)
        }
    }

    @Test
    fun walkingAdvancesStepsAtWalkingCadence() {
        val model = SyntheticSensorModel(seed = 2)
        val frames = (1..10).map { model.frame(state.copy(mode = MotionMode.WALK, speedMps = 1.4), 1_000L * it) }
        val last = frames.last()
        assertEquals(105, last.stepCadence)
        assertTrue("steps=${last.stepCount}", last.stepCount in 15L..20L)
        assertEquals(ActivityKind.WALKING, last.activity)
    }

    @Test
    fun runningHasMoreCadenceAndMotionThanWalking() {
        val walkFrames = SyntheticSensorModel(seed = 3).let { model ->
            (1..10).map { model.frame(state.copy(mode = MotionMode.WALK, speedMps = 1.4), it * 1_000L) }
        }
        val runFrames = SyntheticSensorModel(seed = 3).let { model ->
            (1..10).map { model.frame(state.copy(mode = MotionMode.RUN, speedMps = 3.2), it * 1_000L) }
        }
        val walk = walkFrames.last()
        val run = runFrames.last()
        assertEquals(170, run.stepCadence)
        assertTrue(run.stepCount > walk.stepCount)
        assertTrue(runFrames.maxOf { amplitude(it.accelerometer) } >
            walkFrames.maxOf { amplitude(it.accelerometer) })
        assertEquals(ActivityKind.RUNNING, run.activity)
    }

    @Test
    fun vehicleHasNoStepsAndVehicleActivity() {
        val model = SyntheticSensorModel(seed = 4)
        val frame = (1..10).map { model.frame(state.copy(mode = MotionMode.VEHICLE, speedMps = 12.0), it * 1_000L) }.last()
        assertEquals(ActivityKind.IN_VEHICLE, frame.activity)
        assertEquals(0L, frame.stepCount)
        assertEquals(0, frame.stepCadence)
    }

    @Test
    fun barometerFollowsAltitude() {
        val low = SyntheticSensorModel(seed = 5).frame(state.copy(altitudeMeters = 80.0), 1_000L)
        val high = SyntheticSensorModel(seed = 5).frame(state.copy(altitudeMeters = 800.0), 1_000L)
        assertTrue(low.barometerHpa > high.barometerHpa)
        val expectedLow = 1013.25 * (1.0 - 2.25577e-5 * 80.0).pow(5.25588)
        assertTrue(abs(low.barometerHpa - expectedLow) < 1.0)
    }

    @Test
    fun sameSeedAndTimestampsProduceSameFrames() {
        val first = SyntheticSensorModel(seed = 6)
        val second = SyntheticSensorModel(seed = 6)
        val stateWalk = state.copy(mode = MotionMode.WALK, speedMps = 1.4)
        val firstFrames = (1..20).map { first.frame(stateWalk, it * 1_000L) }
        val secondFrames = (1..20).map { second.frame(stateWalk, it * 1_000L) }
        firstFrames.zip(secondFrames).forEach { (a, b) ->
            assertEquals(a.stepCount, b.stepCount)
            assertEquals(a.barometerHpa, b.barometerHpa, 0f)
            assertTrue(a.accelerometer.contentEquals(b.accelerometer))
        }
    }

    @Test
    fun differentSeedChangesNoise() {
        val first = SyntheticSensorModel(seed = 7).frame(state, 1_000L)
        val second = SyntheticSensorModel(seed = 8).frame(state, 1_000L)
        assertNotEquals(first.accelerometer.toList(), second.accelerometer.toList())
    }

    @Test
    fun frameValuesAreFiniteAndBounded() {
        val frame = SyntheticSensorModel(seed = 9).frame(
            state.copy(mode = MotionMode.RUN, speedMps = 3.0, bearingDeg = 359.0, altitudeMeters = 2500.0),
            5_000L,
        )
        assertTrue(frame.accelerometer.all(Float::isFinite))
        assertTrue(frame.gyroscope.all(Float::isFinite))
        assertTrue(frame.magnetometer.all(Float::isFinite))
        assertTrue(frame.barometerHpa in 300f..1_100f)
        assertTrue(frame.accelerometer.all { abs(it) < 30f })
    }

    private fun magnitude(values: FloatArray): Double =
        sqrt(values.take(3).sumOf { (it.toDouble() * it).toDouble() })

    private fun amplitude(values: FloatArray): Double =
        values.take(3).maxOf { abs(it.toDouble()) }
}
