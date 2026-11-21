package com.system.location.service.maps

/** Compose creation and Fragment callbacks can catch up to the same visible SDK session. */
internal class MapViewLifecycle(private val dispatch: (Event) -> Unit) {
    enum class Event { START, RESUME, PAUSE, STOP }
    private var started = false
    private var resumed = false
    private var closed = false
    private var stopRequested = false

    fun start() {
        stopRequested = false
        if (closed || started) return
        dispatch(Event.START)
        started = true
    }

    fun resume() {
        stopRequested = false
        if (closed || resumed) return
        start()
        dispatch(Event.RESUME)
        resumed = true
    }

    fun pause() {
        if (!resumed) return
        dispatch(Event.PAUSE)
        resumed = false
    }

    fun stop(visible: Boolean = false) {
        stopRequested = true
        if (visible) return
        stopNow()
    }

    fun visibilityChanged(visible: Boolean) {
        if (stopRequested && !visible) stopNow()
    }

    private fun stopNow() {
        stopRequested = false
        pause()
        if (!started) return
        dispatch(Event.STOP)
        started = false
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
    }
}
