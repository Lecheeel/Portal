package com.system.location.service.core.gnss

import java.math.BigDecimal

data class GpsClockEpoch(
    val time: GpsTime,
    val elapsedRealtimeNanos: Long?,
    val hardwareDiscontinuityCount: Int,
    /** Sum of reported 1-sigma terms; null when any necessary term is unknown. */
    val timeUncertaintySeconds: Double?,
    val elapsedRealtimeUncertaintySeconds: Double?,
)

data class GpsClockResult(val epoch: GpsClockEpoch?, val reason: String? = null)

/** Android GnssClock: GPS time = TimeNanos - FullBiasNanos - BiasNanos (when present). */
object GpsReceiverClock {
    private val nanosPerWeek = BigDecimal("604800000000000")
    private val nanosPerSecond = BigDecimal("1000000000")

    fun decode(batch: GnssMeasurementBatch): GpsClockResult {
        val fullBias = batch.fullBiasNanos ?: return GpsClockResult(null, "receiver has no full GPS clock bias")
        // Subtract as integers/decimal BEFORE conversion to Double. Epoch-sized nanoseconds
        // would otherwise lose hundreds of nanoseconds, including the fractional bias entirely.
        val nanos = BigDecimal.valueOf(batch.timeNanos).subtract(BigDecimal.valueOf(fullBias))
            .subtract(BigDecimal.valueOf(batch.biasNanos ?: 0.0))
        if (nanos.signum() < 0) return GpsClockResult(null, "receiver clock precedes GPS epoch")
        val parts = nanos.divideAndRemainder(nanosPerWeek)
        if (parts[0] > BigDecimal.valueOf(Int.MAX_VALUE.toLong()))
            return GpsClockResult(null, "receiver GPS week overflows supported range")
        var week = parts[0].toInt()
        var seconds = parts[1].divide(nanosPerSecond).toDouble()
        if (seconds == GpsTime.SECONDS_PER_WEEK) {
            if (week == Int.MAX_VALUE) return GpsClockResult(null, "receiver GPS week overflow")
            week++
            seconds = 0.0
        }
        val uncertainty = if (batch.timeUncertaintyNanos != null && batch.biasUncertaintyNanos != null)
            (batch.timeUncertaintyNanos + batch.biasUncertaintyNanos) / 1e9 else null
        return GpsClockResult(GpsClockEpoch(GpsTime(week, seconds), batch.elapsedRealtimeNanos,
            batch.hardwareClockDiscontinuityCount, uncertainty,
            batch.elapsedRealtimeUncertaintyNanos?.div(1e9)))
    }
}
