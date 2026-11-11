package com.system.location.service.core.sensors

/** Android sensor type IDs, kept free of platform dependencies for consistency checks. */
object SensorEventValues {
    val continuousTypes = setOf(1, 2, 4, 6, 9, 10, 11, 14, 15, 16, 20, 35)

    fun from(frame: SensorFrame, type: Int, size: Int): FloatArray? {
        val source = when (type) {
            1 -> frame.accelerometer
            2 -> frame.magnetometer
            4 -> frame.gyroscope
            6 -> floatArrayOf(frame.barometerHpa)
            9 -> frame.gravity
            10 -> frame.linearAcceleration
            11, 15, 20 -> frame.rotationVector
            // Uncalibrated values = calibrated values + bias; this model uses zero bias.
            14 -> frame.magnetometer.copyOf(6)
            16 -> frame.gyroscope.copyOf(6)
            35 -> frame.accelerometer.copyOf(6)
            else -> return null
        }
        if (size < source.size || size > 64) return null
        return source.copyOf(size).also {
            if (type in setOf(11, 20) && size > 4) it[4] = -1f // Heading accuracy is unknown.
        }
    }
}
