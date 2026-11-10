package com.system.location.service.core.gnss

enum class GnssCapturePlaybackState { WAITING_FOR_CAPTURE, INVALID_CAPTURE, READY }

data class GnssCapturePreview(
    val state: GnssCapturePlaybackState,
    val frame: ReceiverGnssFrame? = null,
    val issues: List<String> = emptyList(),
)

/** Production model evaluated at the captured observer, not the active route's target location. */
class GnssCapturePreviewer(private val capture: GnssCapture?) {
    private val model by lazy { capture?.let(::ReceiverBroadcastSkyModel) }

    fun current(atMillis: Long, minimumSatellites: Int, elapsedRealtimeNanos: Long? = null,
        currentBootCount: Int? = null): GnssCapturePreview {
        require(atMillis >= 0 && minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        val source = capture ?: return GnssCapturePreview(GnssCapturePlaybackState.WAITING_FOR_CAPTURE,
            issues = listOf("等待采集，当前透传真实卫星数据"))
        val quality = GnssCaptureValidator.validate(source, atMillis)
        if (!quality.eligible) return GnssCapturePreview(GnssCapturePlaybackState.INVALID_CAPTURE,
            issues = quality.issues)
        val observed = source.frames.last { it.observer != null }
        val frame = checkNotNull(model?.frame(atMillis, observed.observer!!,
            observed.altitudeMeters ?: 0.0, minimumSatellites, elapsedRealtimeNanos, currentBootCount))
        return GnssCapturePreview(GnssCapturePlaybackState.READY, frame)
    }
}
