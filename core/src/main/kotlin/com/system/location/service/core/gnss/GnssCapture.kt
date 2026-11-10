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
    /** Per-signal GPS L1/unknown-carrier calibration retained when raw status signals leave IPC. */
    val gpsSignalCalibration: GpsSignalCalibration? = null,
    /** Optional Galileo evidence; requires independently decoded GGTO and GST validity. */
    val galileoBroadcastEvidence: GalileoBroadcastEvidence? = null,
    /** Optional BeiDou evidence; BDT is carried directly and is never inferred from GPS. */
    val beiDouBroadcastEvidence: BeiDouBroadcastEvidence? = null,
    /** Optional GLONASS evidence; UTC(SU)+3h, PZ-90 and receiver datum are explicit. */
    val glonassBroadcastEvidence: GlonassBroadcastEvidence? = null,
) {
    init {
        require(schemaVersion == 1) { "unsupported GNSS capture version" }
        require(bootCount == null || bootCount >= 0) { "invalid capture boot count" }
        gpsBroadcastEvidence?.let { evidence ->
            require(evidence.clock.bootCount == bootCount) { "GPS evidence belongs to another boot" }
            val observedGps = GpsBroadcastEvidence.visibleGpsIdentities(frames)
            require(evidence.ephemerides.all { it.ephemeris.svid in observedGps }) {
                "GPS evidence contains a satellite without valid visible status"
            }
        }
        galileoBroadcastEvidence?.let { evidence ->
            require(evidence.clock.bootCount == bootCount) { "Galileo evidence belongs to another boot" }
            val observedGalileo = frames.flatMap { it.satellites }.filter {
                it.constellation == Constellation.GALILEO && it.elevationDeg >= 0f &&
                    it.azimuthDeg in 0f..<360f && it.cn0DbHz > 0f
            }.map { it.svid }.toSet()
            require(evidence.identities.all { it in observedGalileo }) {
                "Galileo evidence contains a satellite without valid visible status"
            }
        }
        beiDouBroadcastEvidence?.let { evidence ->
            require(evidence.clock.bootCount == bootCount) { "BeiDou evidence belongs to another boot" }
            val observed = frames.flatMap { it.satellites }.filter {
                it.constellation == Constellation.BEIDOU && it.elevationDeg >= 0f &&
                    it.azimuthDeg in 0f..<360f && it.cn0DbHz > 0f
            }.map { it.svid }.toSet()
            require(evidence.identities.all { it in observed }) {
                "BeiDou evidence contains a satellite without valid visible status"
            }
        }
        glonassBroadcastEvidence?.let { evidence ->
            require(evidence.clock.bootCount == bootCount) { "GLONASS evidence belongs to another boot" }
            val observed = frames.flatMap { it.satellites }.filter {
                it.constellation == Constellation.GLONASS && it.elevationDeg >= 0f &&
                    it.azimuthDeg in 0f..<360f && it.cn0DbHz > 0f
            }.map { it.svid }.toSet()
            require(evidence.identities.all { it in observed }) {
                "GLONASS evidence contains a satellite without valid visible status"
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
    /** Raw Android reference identifiers for inter-signal biases; absent is not GPS/L1/C/A. */
    val referenceConstellationTypeForIsb: Int? = null,
    val referenceCarrierHzForIsb: Double? = null,
    val referenceCodeTypeForIsb: String? = null,
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
        require(referenceConstellationTypeForIsb == null || referenceConstellationTypeForIsb >= 0)
        require(referenceCarrierHzForIsb == null || referenceCarrierHzForIsb.isFinite() && referenceCarrierHzForIsb > 0)
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
    /**
     * Android full ISB includes receiver and space-segment components, including time-system,
     * frequency/code and group delays not already compensated in receivedSvTimeNanos.
     * It is not an isolated GGTO. Satellite ISB is part of full ISB, not an additive correction.
     * Raw values/independent availability flags are preserved; no corrections are applied here.
     */
    val fullInterSignalBiasNanos: Double? = null,
    val fullInterSignalBiasUncertaintyNanos: Double? = null,
    val satelliteInterSignalBiasNanos: Double? = null,
    val satelliteInterSignalBiasUncertaintyNanos: Double? = null,
) {
    init {
        require(svid in 1..999) { "invalid measurement SVID" }
        require(listOf(timeOffsetNanos, cn0DbHz, pseudorangeRateMetersPerSecond,
            pseudorangeRateUncertaintyMetersPerSecond, accumulatedDeltaRangeMeters,
            accumulatedDeltaRangeUncertaintyMeters).all { it.isFinite() }) { "non-finite measurement" }
        require(carrierHz == null || carrierHz.isFinite() && carrierHz > 0) { "invalid carrier" }
        require(listOf(fullInterSignalBiasNanos, satelliteInterSignalBiasNanos).all { it == null || it.isFinite() })
        require(listOf(fullInterSignalBiasUncertaintyNanos, satelliteInterSignalBiasUncertaintyNanos)
            .all { it == null || it.isFinite() && it >= 0 }) { "invalid inter-signal bias uncertainty" }
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
