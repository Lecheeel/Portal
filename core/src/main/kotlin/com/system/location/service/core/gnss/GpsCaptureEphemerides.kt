package com.system.location.service.core.gnss

data class GpsCaptureEphemerides(
    val decoded: List<DecodedGpsLnav>,
    val diagnostics: List<String>,
)

/** Decode local capture evidence; no network orbit download, UTC offset guess, or catalog fallback. */
object GpsCaptureEphemerisDecoder {

    fun decode(capture: GnssCapture): GpsCaptureEphemerides {
        val diagnostics = linkedSetOf<String>()
        val clock = CaptureGpsClockAssociation(capture.measurements)
        val assembler = GpsLnavAssembler()
        capture.navigation.filter { it.type == 0x0101 }.forEach { message ->
            // Arrival is only an approximate era/HOW reference. The actual orbit epoch comes
            // from parity-checked HOW/toe/toc, never from treating callback time as transmission.
            val associated = clock.atArrival(message.capturedElapsedRealtimeNanos)
            val reference = associated.reference
            if (reference == null) {
                associated.reason?.let { diagnostics += "GPS $it" }
                return@forEach
            }
            val result = assembler.offer(message, reference.time)
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
