package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.sensors.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SensorTimelineTest {
    @Test fun duplicateAndDelayedReadersCannotAdvanceStepsOrRewindTime() {
        val model = SyntheticSensorModel(tuning = SensorTuning(variability = 0.0))
        val walk = MotionState(MotionMode.WALK, 1.4, 0.0, 0.0)
        model.frame(walk, 0)
        val next = model.frame(walk, 1000)
        assertSame(next, model.frame(walk, 1000))
        assertSame(next, model.frame(walk, 500))
        assertTrue(model.frame(walk, 2000).stepCount > next.stepCount)
    }

    @Test fun gravityAndLinearAccelerationReconstructAccelerometerUnderTilt() {
        val frame = SyntheticSensorModel().frame(
            MotionState(MotionMode.STILL, 0.0, 80.0, 100.0, rollDeg = 40.0, pitchDeg = 20.0), 0)
        for (i in 0..2) assertEquals(frame.accelerometer[i], frame.gravity[i] + frame.linearAcceleration[i], 0.00001f)
        assertEquals(9.81, kotlin.math.sqrt(frame.gravity.sumOf { it.toDouble() * it }), 0.001)
        assertEquals(1.0, frame.rotationVector.sumOf { it.toDouble() * it }, 0.00001)
    }

    @Test fun highRateSamplingProducesWalkingWaveAndConsistentStepCount() {
        val model = SyntheticSensorModel(tuning = SensorTuning(variability = 0.0))
        val walk = MotionState(MotionMode.WALK, 1.4, 0.0, 0.0)
        val frames = (0..1000).map { model.frame(walk, it * 10L) }
        assertTrue(frames.maxOf { it.accelerometer[2] } - frames.minOf { it.accelerometer[2] } > 2.5f)
        val expected = SensorCadence.base(walk.mode, walk.speedMps) / 60 * 10
        assertTrue(abs(frames.last().stepCount - expected) < 1.0)
    }

    @Test fun turningAcrossNorthUsesShortArcAndDuplicateFixesPreserveDerivatives() {
        val tracker = SensorMotionTracker()
        val a = LocationSample(Wgs84(30.0, 120.0), 0.0, 3f, 1f, 359f, 1000, 1_000_000_000)
        tracker.accept(a)
        val b = a.copy(speed = 2f, bearing = 1f, elapsedNanos = 2_000_000_000)
        val motion = tracker.accept(b)
        assertEquals(1.0, motion.forwardAccelerationMps2, 0.001)
        assertEquals(Math.toRadians(2.0), motion.turnRateRadPerSec, 0.001)
        assertEquals(motion, tracker.accept(a))
    }
}
