package com.system.location.service.core.sensors

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample

/** Minimal, short-lived motion grant. Contains neither coordinates nor controller credentials. */
data class SensorPlaybackState(
    val session: String,
    val sampleNanos: Long,
    val expiresNanos: Long,
    val speedMps: Float,
    val bearingDeg: Float,
    val altitudeMeters: Double,
    val tuning: SensorTuning = SensorTuning(),
) {
    init {
        require(session.isNotBlank() && session.length <= 512)
        require(sampleNanos >= 0 && expiresNanos > sampleNanos)
        require(speedMps.isFinite() && speedMps in 0f..1000f)
        require(bearingDeg.isFinite() && bearingDeg in 0f..<360f)
        require(altitudeMeters.isFinite() && altitudeMeters in -500.0..20_000.0)
    }

    fun validAt(nowNanos: Long): Boolean = nowNanos in sampleNanos until expiresNanos
}

/** One timeline per process; listeners never independently advance the step count. */
class SensorPlayback {
    private val tracker = SensorMotionTracker()
    private var model = SyntheticSensorModel()
    private var state: SensorPlaybackState? = null
    private var motion: MotionState? = null

    @Synchronized
    fun update(next: SensorPlaybackState?, nowNanos: Long) {
        if (next == null || !next.validAt(nowNanos)) {
            state = null
            motion = null
            tracker.reset()
            return
        }
        if (state?.session != next.session) {
            tracker.reset()
            model = SyntheticSensorModel(next.tuning.seed)
        }
        model.tuning = next.tuning
        motion = tracker.accept(LocationSample(Wgs84(0.0, 0.0), next.altitudeMeters, 1f,
            next.speedMps, next.bearingDeg, 0, next.sampleNanos))
        state = next
    }

    @Synchronized
    fun frame(nowNanos: Long): SensorFrame? {
        val current = state ?: return null
        if (!current.validAt(nowNanos)) return null
        return model.frame(motion ?: return null, nowNanos / 1_000_000)
    }
}
