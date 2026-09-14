package com.system.location.service.jni

object Dobby {

    external fun setStatus(status: Boolean)
    external fun prepareSensors(): Boolean

}
