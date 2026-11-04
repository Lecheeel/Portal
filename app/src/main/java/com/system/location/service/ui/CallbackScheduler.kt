package com.system.location.service.ui

/** Implementations dispatch and cancel callbacks on the UI thread. */
internal interface CallbackScheduler {
    fun post(delayMillis: Long, callback: Runnable)
    fun remove(callback: Runnable)
}
