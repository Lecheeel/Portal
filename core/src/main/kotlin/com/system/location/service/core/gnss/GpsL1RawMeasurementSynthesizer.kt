package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Target state and clock at one GPS-system epoch; all delays are positive excess path lengths. */
data class GpsL1ReceiverState(
    val observer: Wgs84,
    val ellipsoidHeightMeters: Double,
    val clockBiasSeconds: Double,
    val clockDriftSecondsPerSecond: Double,
    val ionosphericDelayMeters: Double,
    val troposphericDelayMeters: Double,
    val clockUncertaintyNanos: Double,
    val clockBiasUncertaintyNanos: Double,
    val clockDriftUncertaintyNanosPerSecond: Double,
    val elapsedRealtimeUncertaintyNanos: Double,
) {
    init {
        require(ellipsoidHeightMeters.isFinite() && clockBiasSeconds.isFinite() &&
            clockDriftSecondsPerSecond.isFinite())
        require(ionosphericDelayMeters.isFinite() && ionosphericDelayMeters >= 0.0)
        require(troposphericDelayMeters.isFinite() && troposphericDelayMeters >= 0.0)
        require(listOf(clockUncertaintyNanos, clockBiasUncertaintyNanos,
            clockDriftUncertaintyNanosPerSecond, elapsedRealtimeUncertaintyNanos)
            .all { it.isFinite() && it >= 0.0 })
    }
}

/** Measurement quality is supplied by a target-environment signal model, never copied from hardware. */
data class GpsL1SignalQuality(
    val cn0DbHz: Double,
    val receivedSvTimeUncertaintyNanos: Long,
    val pseudorangeRateUncertaintyMetersPerSecond: Double,
    val accumulatedDeltaRangeUncertaintyMeters: Double,
) {
    init {
        require(cn0DbHz.isFinite() && cn0DbHz in 0.0..GnssFrame.MAX_CN0_DB_HZ.toDouble())
        require(receivedSvTimeUncertaintyNanos >= 0L)
        require(pseudorangeRateUncertaintyMetersPerSecond.isFinite() &&
            pseudorangeRateUncertaintyMetersPerSecond >= 0.0)
        require(accumulatedDeltaRangeUncertaintyMeters.isFinite() &&
            accumulatedDeltaRangeUncertaintyMeters >= 0.0)
    }
}

/**
 * GPS L1 C/A raw observations from explicit target-world inputs. This is a vacuum/orbit model
 * plus caller-supplied atmospheric delays and signal quality; it does not claim RF propagation
 * or generate navigation bits. Missing any required input fails closed.
 */
class GpsL1RawMeasurementSynthesizer(
    ephemerides: List<GpsLnavEphemeris>,
    private val receiverAt: (GpsTime) -> GpsL1ReceiverState?,
    private val qualityAt: (GpsLnavEphemeris, Double) -> GpsL1SignalQuality?,
) {
    private data class PhaseTrack(val firstPhaseRangeMeters: Double, val lastTime: GpsTime)

    private val ephemerides = ephemerides.toList()
    private val tracks = mutableMapOf<Int, PhaseTrack>()
    private var lastEpoch: GpsTime? = null
    private var lastDiscontinuityCount: Int? = null

    init {
        require(this.ephemerides.isNotEmpty())
        require(this.ephemerides.all { it.health == 0 }) { "unhealthy GPS ephemeris cannot be synthesized" }
        require(this.ephemerides.map { it.svid }.distinct().size == this.ephemerides.size)
    }

    @Synchronized
    fun synthesize(
        receptionTime: GpsTime,
        elapsedRealtimeNanos: Long,
        capturedAtMillis: Long,
        hardwareClockDiscontinuityCount: Int,
    ): GnssMeasurementBatch? {
        require(elapsedRealtimeNanos >= 0L && capturedAtMillis >= 0L && hardwareClockDiscontinuityCount >= 0)
        val previousEpoch = lastEpoch
        if (previousEpoch != null && receptionTime.secondsSince(previousEpoch) <= 0.0) return null
        val clockReset = lastDiscontinuityCount?.let { it != hardwareClockDiscontinuityCount } ?: true
        if (clockReset || previousEpoch?.let { receptionTime.secondsSince(it) > MAX_TRACK_GAP_SECONDS } == true) {
            tracks.clear()
        }
        val nextTracks = tracks.toMutableMap()
        val measurements = ephemerides.mapNotNull { ephemeris ->
            val center = observationAt(ephemeris, receptionTime) ?: return@mapNotNull null
            if (!center.geometry.lineOfSight.aboveHorizon) return@mapNotNull null
            val quality = qualityAt(ephemeris, center.geometry.lineOfSight.elevationDegrees) ?: return@mapNotNull null
            val rate = pseudorangeRate(ephemeris, receptionTime) ?: return@mapNotNull null
            val phaseTrack = nextTracks[ephemeris.svid]
            val reset = phaseTrack == null || receptionTime.secondsSince(phaseTrack.lastTime) > MAX_TRACK_GAP_SECONDS
            val anchor = if (reset) center.carrierRangeMeters else requireNotNull(phaseTrack).firstPhaseRangeMeters
            val adr = center.carrierRangeMeters - anchor
            nextTracks[ephemeris.svid] = PhaseTrack(anchor, receptionTime)

            val satelliteClockTime = receptionTime.plusSeconds(
                center.receiver.clockBiasSeconds - center.codePseudorangeMeters / SPEED_OF_LIGHT,
            )
            val svTimeNanos = secondsOfWeekNanos(satelliteClockTime.secondsOfWeek)
            GnssMeasurementObservation(
                constellation = Constellation.GPS,
                svid = ephemeris.svid,
                state = STATE_CODE_LOCK or STATE_TOW_KNOWN,
                timeOffsetNanos = 0.0,
                receivedSvTimeNanos = svTimeNanos,
                receivedSvTimeUncertaintyNanos = quality.receivedSvTimeUncertaintyNanos,
                cn0DbHz = quality.cn0DbHz,
                pseudorangeRateMetersPerSecond = rate,
                pseudorangeRateUncertaintyMetersPerSecond = quality.pseudorangeRateUncertaintyMetersPerSecond,
                accumulatedDeltaRangeState = ADR_STATE_VALID or if (reset) ADR_STATE_RESET else 0,
                accumulatedDeltaRangeMeters = adr,
                accumulatedDeltaRangeUncertaintyMeters = quality.accumulatedDeltaRangeUncertaintyMeters,
                carrierHz = GPS_L1_CA_HZ,
                codeType = GPS_L1_CA_CODE_TYPE,
            )
        }.sortedBy { it.svid }

        if (measurements.size < MIN_MEASUREMENTS) return null
        val receiver = receiverAt(receptionTime) ?: return null
        val localGpsNanos = gpsEpochNanos(receptionTime).add(secondsNanos(receiver.clockBiasSeconds))
        val fullBiasDecimal = BigDecimal.valueOf(elapsedRealtimeNanos).subtract(localGpsNanos)
        val fullBias = fullBiasDecimal.setScale(0, RoundingMode.FLOOR).longValueExact()
        val biasNanos = fullBiasDecimal.subtract(BigDecimal.valueOf(fullBias)).toDouble()
        val batch = GnssMeasurementBatch(
            capturedAtMillis = capturedAtMillis,
            timeNanos = elapsedRealtimeNanos,
            fullBiasNanos = fullBias,
            biasNanos = biasNanos,
            hardwareClockDiscontinuityCount = hardwareClockDiscontinuityCount,
            measurements = measurements,
            timeUncertaintyNanos = receiver.clockUncertaintyNanos,
            biasUncertaintyNanos = receiver.clockBiasUncertaintyNanos,
            driftNanosPerSecond = receiver.clockDriftSecondsPerSecond * 1e9,
            driftUncertaintyNanosPerSecond = receiver.clockDriftUncertaintyNanosPerSecond,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            elapsedRealtimeUncertaintyNanos = receiver.elapsedRealtimeUncertaintyNanos,
            referenceConstellationTypeForIsb = GPS_CONSTELLATION_TYPE,
            referenceCarrierHzForIsb = GPS_L1_CA_HZ,
            referenceCodeTypeForIsb = GPS_L1_CA_CODE_TYPE,
        )
        tracks.clear()
        tracks.putAll(nextTracks)
        lastEpoch = receptionTime
        lastDiscontinuityCount = hardwareClockDiscontinuityCount
        return batch
    }

    private data class Observation(
        val geometry: GpsReceptionObservation,
        val receiver: GpsL1ReceiverState,
        val codePseudorangeMeters: Double,
        val carrierRangeMeters: Double,
    )

    private fun observationAt(ephemeris: GpsLnavEphemeris, time: GpsTime): Observation? {
        val receiver = receiverAt(time) ?: return null
        val geometry = GpsReceptionGeometry.observe(ephemeris, time, receiver.observer,
            receiver.ellipsoidHeightMeters).observation ?: return null
        if (!geometry.lineOfSight.aboveHorizon) return null
        val common = SPEED_OF_LIGHT * (receiver.clockBiasSeconds - geometry.l1CaClockBiasSeconds) +
            receiver.troposphericDelayMeters
        val codeRange = geometry.lineOfSight.rangeMeters + common + receiver.ionosphericDelayMeters
        val carrierRange = geometry.lineOfSight.rangeMeters +
            SPEED_OF_LIGHT * (receiver.clockBiasSeconds - geometry.clockBiasSeconds) +
            receiver.troposphericDelayMeters - receiver.ionosphericDelayMeters
        if (!codeRange.isFinite() || codeRange <= 0.0 || !carrierRange.isFinite()) return null
        return Observation(geometry, receiver, codeRange, carrierRange)
    }

    private fun pseudorangeRate(ephemeris: GpsLnavEphemeris, time: GpsTime): Double? {
        val before = observationAt(ephemeris, time.plusSeconds(-RATE_HALF_INTERVAL_SECONDS))
            ?: return null
        val after = observationAt(ephemeris, time.plusSeconds(RATE_HALF_INTERVAL_SECONDS))
            ?: return null
        val rate = (after.codePseudorangeMeters - before.codePseudorangeMeters) /
            (2 * RATE_HALF_INTERVAL_SECONDS)
        return rate.takeIf(Double::isFinite)
    }

    private fun gpsEpochNanos(time: GpsTime): BigDecimal =
        BigDecimal.valueOf(time.week.toLong()).multiply(BigDecimal.valueOf(GPS_WEEK_NANOS))
            .add(secondsNanos(time.secondsOfWeek))

    private fun secondsOfWeekNanos(seconds: Double): Long =
        BigDecimal.valueOf(seconds).movePointRight(9).setScale(0, RoundingMode.HALF_EVEN)
            .longValueExact().mod(GPS_WEEK_NANOS)

    private fun secondsNanos(seconds: Double): BigDecimal =
        BigDecimal.valueOf(seconds).movePointRight(9)

    companion object {
        const val GPS_L1_CA_HZ = 1_575_420_000.0
        const val GPS_L1_CA_CODE_TYPE = "C"
        const val GPS_CONSTELLATION_TYPE = 1
        const val MIN_MEASUREMENTS = 4
        const val MAX_TRACK_GAP_SECONDS = 2.0
        private const val SPEED_OF_LIGHT = GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND
        private const val GPS_WEEK_NANOS = 604_800_000_000_000L
        private const val RATE_HALF_INTERVAL_SECONDS = 0.05
        private const val STATE_CODE_LOCK = 1 shl 0
        private const val STATE_TOW_KNOWN = 1 shl 14
        private const val ADR_STATE_VALID = 1 shl 0
        private const val ADR_STATE_RESET = 1 shl 1
    }
}
