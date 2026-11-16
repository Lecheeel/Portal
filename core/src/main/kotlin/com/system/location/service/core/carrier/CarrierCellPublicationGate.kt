package com.system.location.service.core.carrier

/** Coalesces rapid location publications and emits only after the modeled geographic tile changes. */
class CarrierCellPublicationGate(private val intervalNanos: Long = 1_500_000_000L) {
    private var lastTile: Pair<Long, Long>? = null
    private var lastNanos: Long? = null

    @Synchronized
    fun changed(latitude: Double, longitude: Double, now: Long): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || now < 0) return false
        val tile = kotlin.math.floor(latitude * 200).toLong() to kotlin.math.floor(longitude * 200).toLong()
        val before = lastNanos
        if (tile == lastTile) return false
        if (before != null && now >= before && now - before < intervalNanos) return false
        lastTile = tile
        lastNanos = now
        return true
    }
}
