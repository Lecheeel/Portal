package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Receiver epoch associated with Android's elapsed clock on one boot. Advancing by elapsed SI
 * seconds is a geometric simulation time model, not a disciplined real-time GPS clock. Reported
 * uncertainties describe the anchor only; they are not a bound on future oscillator error.
 * No wall-clock, UTC, leap-second or week-number guess enters this association.
 */
@Serializable
data class GpsElapsedClockEvidence(
    val bootCount: Int,
    val gpsWeek: Int,
    val gpsSecondsOfWeek: Double,
    val elapsedRealtimeNanos: Long,
    val timeUncertaintySeconds: Double,
    val elapsedUncertaintySeconds: Double,
) {
    init {
        require(bootCount >= 0 && elapsedRealtimeNanos >= 0)
        GpsTime(gpsWeek, gpsSecondsOfWeek)
        require(timeUncertaintySeconds.isFinite() && timeUncertaintySeconds in 0.0..MAX_ANCHOR_UNCERTAINTY_SECONDS)
        require(elapsedUncertaintySeconds.isFinite() && elapsedUncertaintySeconds in 0.0..MAX_ANCHOR_UNCERTAINTY_SECONDS)
    }

    fun at(elapsedNanos: Long, currentBootCount: Int?): GpsTime? {
        if (currentBootCount != bootCount || elapsedNanos < elapsedRealtimeNanos) return null
        val delta = (elapsedNanos - elapsedRealtimeNanos) / 1e9
        if (delta > MAX_EXTRAPOLATION_SECONDS) return null
        return runCatching { GpsTime(gpsWeek, gpsSecondsOfWeek).plusSeconds(delta) }.getOrNull()
    }

    companion object {
        // Simulation clock freshness/quality policies, not broadcast fit or guaranteed clock accuracy.
        const val MAX_EXTRAPOLATION_SECONDS = 1800.0
        const val MAX_ANCHOR_UNCERTAINTY_SECONDS = 1e-3

        fun fromCapture(capture: GnssCapture): GpsElapsedClockEvidence? {
            val boot = capture.bootCount ?: return null
            if (capture.measurements.isEmpty()) return null
            // Do not hide a reset in a batch which was unusable as an anchor.
            if (capture.measurements.map { it.hardwareClockDiscontinuityCount }.distinct().size != 1) return null
            val anchors = capture.measurements.mapNotNull { batch ->
                if (batch.capturedAtMillis !in capture.startedAtMillis..capture.endedAtMillis) return@mapNotNull null
                val e = GpsReceiverClock.decode(batch).epoch ?: return@mapNotNull null
                val elapsed = e.elapsedRealtimeNanos ?: return@mapNotNull null
                val arrival = batch.capturedElapsedRealtimeNanos ?: return@mapNotNull null
                val clockUncertainty = e.timeUncertaintySeconds ?: return@mapNotNull null
                val elapsedUncertainty = e.elapsedRealtimeUncertaintySeconds ?: return@mapNotNull null
                if (arrival < elapsed || arrival - elapsed > 10_000_000_000L ||
                    clockUncertainty > MAX_ANCHOR_UNCERTAINTY_SECONDS || elapsedUncertainty > MAX_ANCHOR_UNCERTAINTY_SECONDS)
                    return@mapNotNull null
                e
            }.sortedBy { it.elapsedRealtimeNanos }
            if (anchors.size < 2) return null
            val last = anchors.last()
            if (last.elapsedRealtimeNanos!! - anchors.first().elapsedRealtimeNanos!! < 30_000_000_000L) return null
            if (anchors.any { e ->
                val delta = (last.elapsedRealtimeNanos - e.elapsedRealtimeNanos!!) / 1e9
                abs(last.time.secondsSince(e.time) - delta) > MAX_ANCHOR_UNCERTAINTY_SECONDS
            }) return null
            return GpsElapsedClockEvidence(boot, last.time.week, last.time.secondsOfWeek, last.elapsedRealtimeNanos,
                last.timeUncertaintySeconds!!, last.elapsedRealtimeUncertaintySeconds!!)
        }
    }
}
