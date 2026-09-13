package com.system.location.service.android.coro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class CoroutineRouteMock {
    private val paused = MutableStateFlow(true)
    val isPaused: Boolean get() = paused.value

    suspend fun routeMockCoroutine() { paused.first { !it } }
    fun pause() { paused.value = true }
    fun resume() { paused.value = false }
}
