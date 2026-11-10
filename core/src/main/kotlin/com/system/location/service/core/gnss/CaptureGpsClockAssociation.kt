package com.system.location.service.core.gnss

import kotlin.math.abs

data class CaptureGpsReference(val time: GpsTime, val hardwareDiscontinuityCount: Int)
data class CaptureGpsReferenceResult(val reference: CaptureGpsReference?, val reason: String? = null)

/**
 * Approximate GPS era/time association at navigation callback ARRIVAL. It is not the signal
 * emission time, a precise reception epoch, or a bound on the navigation callback's latency.
 * Known clock uncertainties and contemporaneous monotonic anchors are required.
 */
class CaptureGpsClockAssociation(batches: List<GnssMeasurementBatch>) {
    private val anchors = batches.mapNotNull { batch ->
        val epoch = GpsReceiverClock.decode(batch).epoch ?: return@mapNotNull null
        val elapsed = epoch.elapsedRealtimeNanos ?: return@mapNotNull null
        val arrival = batch.capturedElapsedRealtimeNanos ?: return@mapNotNull null
        val uncertainty = epoch.timeUncertaintySeconds ?: return@mapNotNull null
        val elapsedUncertainty = epoch.elapsedRealtimeUncertaintySeconds ?: return@mapNotNull null
        if (uncertainty > 1 || elapsedUncertainty > 1 || arrival < elapsed || arrival - elapsed > 10_000_000_000L)
            return@mapNotNull null
        epoch
    }

    fun atArrival(elapsedNanos: Long?): CaptureGpsReferenceResult {
        fun invalid(reason: String) = CaptureGpsReferenceResult(null, reason)
        if (elapsedNanos == null || elapsedNanos < 0) return invalid("导航电文缺少有效单调到达时刻，未推测完整周号")
        val nearby = anchors.filter { abs(elapsedNanos - it.elapsedRealtimeNanos!!) <= 10_000_000_000L }
        if (nearby.isEmpty()) return invalid("电文附近没有具有已知不确定度的完整接收机时钟")
        if (nearby.map { it.hardwareDiscontinuityCount }.distinct().size != 1)
            return invalid("电文附近接收机时钟发生不连续，未跨重置关联")
        val estimates = nearby.map { anchor ->
            runCatching { anchor.time.plusSeconds((elapsedNanos - anchor.elapsedRealtimeNanos!!).toDouble() / 1e9) }
                .getOrNull() ?: return invalid("接收机时钟关联超出完整 GPS 周号范围")
        }
        val time = estimates.first()
        if (estimates.any { abs(it.secondsSince(time)) > 1 }) return invalid("接收机时钟与单调时钟关联不一致")
        return CaptureGpsReferenceResult(CaptureGpsReference(time, nearby.first().hardwareDiscontinuityCount))
    }
}
