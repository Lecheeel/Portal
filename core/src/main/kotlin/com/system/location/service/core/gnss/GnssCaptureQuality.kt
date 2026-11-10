package com.system.location.service.core.gnss

/** Explicit eligibility: raw messages being present does not mean a complete ephemeris exists. */
data class GnssCaptureQuality(val eligible: Boolean, val issues: List<String>)

object GnssCaptureValidator {
    const val MIN_FRAMES = 20
    const val MIN_WINDOW_MILLIS = 30_000L
    const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

    fun validate(capture: GnssCapture, nowMillis: Long): GnssCaptureQuality {
        val issues = mutableListOf<String>()
        val usable = capture.frames.filter { it.satellites.any(::isUsableSatellite) }
        if (usable.size < MIN_FRAMES) issues += "有效帧不足 $MIN_FRAMES 帧"
        if (usable.isEmpty() || usable.last().capturedAtMillis - usable.first().capturedAtMillis < MIN_WINDOW_MILLIS) {
            issues += "有效观测窗口不足 30 秒（需要仰角、方位角和正 CN0）"
        }
        if (nowMillis < capture.endedAtMillis || nowMillis - capture.endedAtMillis > MAX_AGE_MILLIS) {
            issues += "采集画像已过期或系统时间异常"
        }
        if (usable.none { it.observer != null }) issues += "缺少真实观测坐标"
        if (capture.frames.any { it.satellites.any { satellite -> satellite.elevationDeg < 0f } }) {
            issues += "采集包含地平线以下卫星，请重新采集"
        }
        return GnssCaptureQuality(issues.isEmpty(), issues)
    }

    /** CN0=0 is an Android placeholder, not evidence of a received signal. */
    fun isUsableSatellite(satellite: GnssSatellite): Boolean =
        satellite.elevationDeg >= 0f && satellite.azimuthDeg in 0f..<360f && satellite.cn0DbHz > 0f
}
