package com.system.location.service.jni

object Dobby {

    external fun setStatus(status: Boolean)
    external fun setMotionState(mode: Int, speed: Float, bearing: Float, altitude: Float)
    external fun prepareSensors(): Boolean

}
