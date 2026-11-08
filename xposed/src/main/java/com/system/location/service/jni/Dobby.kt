package com.system.location.service.jni

object Dobby {
    external fun setStatus(status: Boolean)
    external fun setSensorFrame(
        stepCount: Long,
        stepCadence: Int,
        syncNanos: Long,
        accelerometer: FloatArray,
        gyroscope: FloatArray,
        magnetometer: FloatArray,
        barometerHpa: Float,
    )
    external fun prepareSensors(): Boolean
}
