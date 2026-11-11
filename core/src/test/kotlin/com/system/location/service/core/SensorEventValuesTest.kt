package com.system.location.service.core

import com.system.location.service.core.sensors.*
import org.junit.Assert.*
import org.junit.Test

class SensorEventValuesTest {
    private val frame = SyntheticSensorModel().frame(MotionState(MotionMode.WALK, 1.5, 90.0, 50.0), 1000)

    @Test fun uncalibratedChannelsAgreeWithCalibratedValuesAndBiases() {
        listOf(1 to 35, 2 to 14, 4 to 16).forEach { (calibrated, uncalibrated) ->
            val values = SensorEventValues.from(frame, uncalibrated, 6)!!
            val corrected = FloatArray(3) { values[it] - values[it + 3] }
            assertArrayEquals(SensorEventValues.from(frame, calibrated, 3), corrected, 0f)
        }
    }

    @Test fun rotationVectorUsesAndroidOrderAndUnknownHeadingAccuracy() {
        val vector = SensorEventValues.from(frame, 11, 5)!!
        assertEquals(-1f, vector[4], 0f)
        assertEquals(1.0, vector.take(4).sumOf { it.toDouble() * it }, 1e-6)
        assertArrayEquals(frame.rotationVector, SensorEventValues.from(frame, 15, 4), 0f)
        assertNull(SensorEventValues.from(frame, 16, 3))
        assertNull(SensorEventValues.from(frame, 999, 16))
    }
}
