package com.system.location.service.core.gnss

import kotlin.math.abs

data class GpsCaptureEphemerides(
    val decoded: List<DecodedGpsLnav>,
    val diagnostics: List<String>,
)

/** Decode local capture evidence; no network orbit download, UTC offset guess, or catalog fallback. */
object GpsCaptureEphemerisDecoder {

    fun decode(capture: GnssCapture): GpsCaptureEphemerides {
        val diagnostics = linkedSetOf<String>()
        val anchors = capture.measurements.mapNotNull { batch ->
            val epoch = GpsReceiverClock.decode(batch).epoch ?: return@mapNotNull null
            val elapsed = epoch.elapsedRealtimeNanos ?: return@mapNotNull null
            val arrival = batch.capturedElapsedRealtimeNanos ?: return@mapNotNull null
            val uncertainty = epoch.timeUncertaintySeconds ?: return@mapNotNull null
            val elapsedUncertainty = epoch.elapsedRealtimeUncertaintySeconds ?: return@mapNotNull null
            if (uncertainty > 1 || elapsedUncertainty > 1 || arrival < elapsed || arrival - elapsed > 10_000_000_000L)
                return@mapNotNull null
            epoch
        }
        val assembler = GpsLnavAssembler()
        capture.navigation.filter { it.type == 0x0101 }.forEach { message ->
            val elapsed = message.capturedElapsedRealtimeNanos
            if (elapsed == null) {
                diagnostics += "GPS 导航电文缺少单调到达时刻，未推测完整周号"
                return@forEach
            }
            // Arrival is only an approximate era/HOW reference. The actual orbit epoch comes
            // from parity-checked HOW/toe/toc, never from treating callback time as transmission.
            val nearby = anchors.filter { anchor ->
                abs(elapsed.toDouble() - anchor.elapsedRealtimeNanos!!.toDouble()) <= 10e9
            }
            if (nearby.isEmpty()) {
                diagnostics += "GPS 电文附近没有具有已知不确定度的完整接收机时钟"
                return@forEach
            }
            if (nearby.map { it.hardwareDiscontinuityCount }.distinct().size != 1) {
                diagnostics += "GPS 电文附近接收机时钟发生不连续，未跨重置关联"
                return@forEach
            }
            val estimates = nearby.map { anchor ->
                anchor.time.plusSeconds((elapsed - anchor.elapsedRealtimeNanos!!).toDouble() / 1e9)
            }
            val reference = estimates.first()
            if (estimates.any { abs(it.secondsSince(reference)) > 1 }) {
                diagnostics += "GPS 接收机时钟与单调时钟关联不一致"
                return@forEach
            }
            val result = assembler.offer(message, reference)
            // Waiting for another subframe is diagnostic evidence, not a corrupt capture.
            result.reason?.let { diagnostics += "GPS ${message.svid}：$it" }
        }
        val decoded = assembler.completed().groupBy { data ->
            val e = data.ephemeris
            Triple(e.svid, e.toe.week, e.toe.secondsOfWeek)
        }.mapNotNull { (_, repeated) ->
            if (repeated.map { it.ephemeris }.distinct().size != 1) {
                diagnostics += "GPS ${repeated.first().ephemeris.svid}：同一星历历元存在冲突参数"
                null
            } else repeated.last()
        }
        return GpsCaptureEphemerides(decoded, diagnostics.toList())
    }
}
