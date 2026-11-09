package com.system.location.service.ui.home

/** Camera requests never select a simulation target. All positions are trusted WGS-84 fixes. */
internal class HomeCameraPolicy {
    data class Position(val latitude: Double, val longitude: Double)
    data class Move(val position: Position, val zoom: Float = 16.5f)

    private var pending = true
    private var position: Position? = null
    private var observedAt = 0L

    fun accept(
        latitude: Double,
        longitude: Double,
        ageMillis: Long,
        nowMillis: Long,
        isMock: Boolean,
        simulationActive: Boolean,
    ): Move? {
        if (isMock || simulationActive || ageMillis !in 0..MAX_FIX_AGE_MILLIS ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            latitude == 0.0 && longitude == 0.0) return null
        position = Position(latitude, longitude)
        observedAt = nowMillis - ageMillis
        return if (pending) Move(position!!).also { pending = false } else null
    }

    fun locate(nowMillis: Long): Move? {
        val current = position
        if (current != null && nowMillis - observedAt in 0..MAX_FIX_AGE_MILLIS) {
            pending = false
            return Move(current)
        }
        pending = true
        return null
    }

    fun userMovedCamera() { pending = false }

    companion object { const val MAX_FIX_AGE_MILLIS = 120_000L }
}
