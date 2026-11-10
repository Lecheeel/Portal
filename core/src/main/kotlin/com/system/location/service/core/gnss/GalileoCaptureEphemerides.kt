package com.system.location.service.core.gnss

data class GalileoCaptureEphemerides(
    val inav: List<DecodedGalileoInav>,
    val fnav: List<DecodedGalileoFnav>,
    val diagnostics: List<String>,
)

/**
 * Connects captured Android clocks/navigation to the bounded Galileo assemblers.
 * A separately established, currently applicable GGTO model is mandatory; no zero-offset
 * fallback or hardcoded UTC leap offset is used. Callback arrival supplies only approximate
 * full GST for era/page association, never exact transmission time or a latency bound.
 * Captures spanning clock resets or more than the 120s assembly policy are rejected.
 */
object GalileoCaptureEphemerisDecoder {
    fun decode(capture: GnssCapture, gpsToGst: GalileoGpsTimeOffset?): GalileoCaptureEphemerides {
        val diagnostics = linkedSetOf<String>()
        fun empty(reason: String) = GalileoCaptureEphemerides(emptyList(), emptyList(), (diagnostics + reason).toList())
        val messages = capture.navigation.filter { it.type == 0x0601 || it.type == 0x0602 }
        if (messages.isEmpty()) return empty("采集窗口没有 Galileo I/NAV 或 F/NAV 电文")
        if (gpsToGst == null) return empty("缺少有适用时段依据的 GGTO，未将 GPS 时钟冒充 GST")
        val clock = CaptureGpsClockAssociation(capture.measurements)
        val quarantined = mutableSetOf<Pair<Int, Int>>() // satellite + message format
        data class Associated(val message: GnssNavigationObservation, val time: GalileoTime, val domain: Int)
        val associated = messages.mapNotNull { message ->
            val result = clock.atArrival(message.capturedElapsedRealtimeNanos)
            val reference = result.reference
            val converted = reference?.let { gpsToGst.gpsToGalileo(it.time) }
            val time = converted?.time
            if (time == null) {
                diagnostics += "Galileo ${message.svid}：${result.reason ?: converted?.reason ?: "时标关联失败"}"
                // Missing timing of a possibly newer service page must not restore older healthy evidence.
                quarantined += message.svid to message.type
                null
            } else Associated(message, time, reference.hardwareDiscontinuityCount)
        }
        if (associated.isEmpty()) return empty("没有可关联完整 GST 的 Galileo 导航电文")
        if (associated.map { it.domain }.distinct().size != 1)
            return empty("Galileo 采集跨接收机时钟重置，未拼接不同连续时钟域的星历")
        val earliest = associated.minWith(compareBy<Associated> { it.time.week }.thenBy { it.time.secondsOfWeek }).time
        val latest = associated.maxWith(compareBy<Associated> { it.time.week }.thenBy { it.time.secondsOfWeek }).time
        if (latest.secondsSince(earliest) > 120) return empty("Galileo 采集超过 120 秒有界组帧窗口")
        val inav = GalileoInavAssembler(earliest, latest)
        val fnav = GalileoFnavAssembler(earliest, latest)
        associated.forEach { (message, time) ->
            val reason = if (message.type == 0x0601) inav.offer(message, time).reason else fnav.offer(message, time).reason
            reason?.let { diagnostics += "Galileo ${message.svid}：$it" }
        }
        return GalileoCaptureEphemerides(
            inav.completed().filter { (it.ephemeris.svid to 0x0601) !in quarantined },
            fnav.completed().filter { (it.ephemeris.svid to 0x0602) !in quarantined }, diagnostics.toList())
    }
}
