package com.system.location.service.core.gnss

import java.util.Base64
import kotlinx.serialization.Serializable

/** Receiver evidence is retained separately from statistics so correlations can be replayed. */
@Serializable
data class GnssCapture(
    val schemaVersion: Int = 1,
    val deviceModel: String,
    val osVersion: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val frames: List<GnssObservationFrame>,
    val measurements: List<GnssMeasurementBatch> = emptyList(),
    val navigation: List<GnssNavigationObservation> = emptyList(),
    val notes: List<String> = emptyList(),
    /** Android BOOT_COUNT; missing in legacy captures. Elapsed-time evidence cannot cross boots. */
    val bootCount: Int? = null,
    /** Decoded compact evidence; populated at the authenticated transport boundary. */
    val gpsBroadcastEvidence: GpsBroadcastEvidence? = null,
) {
    init {
        require(schemaVersion == 1) { "unsupported GNSS capture version" }
        require(bootCount == null || bootCount >= 0) { "invalid capture boot count" }
        gpsBroadcastEvidence?.let { evidence ->
            require(evidence.clock.bootCount == bootCount) { "GPS evidence belongs to another boot" }
            val observedGps = frames.flatMap { it.satellites }.filter {
                it.constellation == Constellation.GPS
            }.map { it.svid }.toSet()
            require(evidence.ephemerides.all { it.ephemeris.svid in observedGps }) {
                "GPS evidence contains an unobserved satellite"
            }
        }
        require(deviceModel.isNotBlank() && osVersion.isNotBlank()) { "missing device metadata" }
        require(startedAtMillis >= 0 && endedAtMillis >= startedAtMillis) { "invalid capture interval" }
        require(frames.isNotEmpty() && frames.size <= MAX_FRAMES) { "invalid frame count" }
        require(frames.all { it.capturedAtMillis in startedAtMillis..endedAtMillis }) {
            "observation outside capture interval"
        }
        require(frames.zipWithNext().all { (a, b) -> a.capturedAtMillis <= b.capturedAtMillis }) {
            "frames must be chronological"
        }
        require(measurements.size <= MAX_FRAMES && navigation.size <= MAX_NAVIGATION_MESSAGES) {
            "capture exceeds raw data limit"
        }
    }

    fun profile(): GnssObservationProfile = GnssObservationProfileBuilder.build(frames, deviceModel, osVersion)

    companion object {
        const val MAX_FRAMES = 600
        const val MAX_NAVIGATION_MESSAGES = 4096
    }
}

@Serializable
data class GnssMeasurementBatch(
    val capturedAtMillis: Long,
    val timeNanos: Long,
    val fullBiasNanos: Long?,
    val biasNanos: Double?,
    val hardwareClockDiscontinuityCount: Int,
    val measurements: List<GnssMeasurementObservation>,
    val timeUncertaintyNanos: Double? = null,
    val biasUncertaintyNanos: Double? = null,
    val driftNanosPerSecond: Double? = null,
    val driftUncertaintyNanosPerSecond: Double? = null,
    val leapSecond: Int? = null,
    /** Receiver event's epoch on Android's elapsed realtime clock, API 29+. */
    val elapsedRealtimeNanos: Long? = null,
    val elapsedRealtimeUncertaintyNanos: Double? = null,
    /** Callback arrival time, distinct from the receiver event epoch. */
    val capturedElapsedRealtimeNanos: Long? = null,
) {
    init {
        require(capturedAtMillis >= 0 && measurements.size <= 512) { "invalid measurement batch" }
        require(biasNanos == null || biasNanos.isFinite()) { "invalid clock bias" }
        require(listOf(timeUncertaintyNanos, biasUncertaintyNanos, driftUncertaintyNanosPerSecond,
            elapsedRealtimeUncertaintyNanos).all { it == null || it.isFinite() && it >= 0 }) {
            "invalid clock uncertainty"
        }
        require(driftNanosPerSecond == null || driftNanosPerSecond.isFinite()) { "invalid clock drift" }
        require(elapsedRealtimeNanos == null || elapsedRealtimeNanos >= 0)
        require(capturedElapsedRealtimeNanos == null || capturedElapsedRealtimeNanos >= 0)
    }
}

@Serializable
data class GnssMeasurementObservation(
    val constellation: Constellation,
    val svid: Int,
    val state: Int,
    val timeOffsetNanos: Double,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0DbHz: Double,
    val pseudorangeRateMetersPerSecond: Double,
    val pseudorangeRateUncertaintyMetersPerSecond: Double,
    val accumulatedDeltaRangeState: Int,
    val accumulatedDeltaRangeMeters: Double,
    val accumulatedDeltaRangeUncertaintyMeters: Double,
    val carrierHz: Double? = null,
    val codeType: String? = null,
) {
    init {
        require(svid in 1..999) { "invalid measurement SVID" }
        require(listOf(timeOffsetNanos, cn0DbHz, pseudorangeRateMetersPerSecond,
            pseudorangeRateUncertaintyMetersPerSecond, accumulatedDeltaRangeMeters,
            accumulatedDeltaRangeUncertaintyMeters).all { it.isFinite() }) { "non-finite measurement" }
        require(carrierHz == null || carrierHz.isFinite() && carrierHz > 0) { "invalid carrier" }
    }
}

@Serializable
data class GnssNavigationObservation(
    val capturedAtMillis: Long,
    val type: Int,
    val svid: Int,
    val status: Int,
    val messageId: Int,
    val submessageId: Int,
    val dataBase64: String,
    val capturedElapsedRealtimeNanos: Long? = null,
) {
    init {
        require(capturedAtMillis >= 0 && svid in 1..999) { "invalid navigation metadata" }
        require(dataBase64.length <= 8192) { "navigation payload too large" }
        require(Base64.getDecoder().decode(dataBase64).isNotEmpty()) { "empty navigation payload" }
        require(capturedElapsedRealtimeNanos == null || capturedElapsedRealtimeNanos >= 0)
    }
}
