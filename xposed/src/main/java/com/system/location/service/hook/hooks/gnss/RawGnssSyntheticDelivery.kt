package com.system.location.service.hook.hooks.gnss

import android.location.GnssMeasurementsEvent
import android.os.SystemClock
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.gnss.GpsL1RawMeasurementSynthesizer
import com.system.location.service.core.gnss.GpsL1ReceiverState
import com.system.location.service.core.gnss.GpsL1SignalQuality
import com.system.location.service.core.gnss.GpsLnavEphemeris
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.core.gnss.RawGnssSynthesis
import com.system.location.service.hook.utils.ProviderReadContext

/**
 * Per-route GPS L1 synthetic observation pipeline. One synthesizer per route preserves ADR phase
 * continuity across epochs, while a changed ephemeris set starts a new track and a fresh ADR reset.
 * The frame that drives GNSS status/NMEA is the same frame that drives these measurements.
 */
internal object RawGnssSyntheticDelivery {
    private class Track(
        val ephemerides: List<GpsLnavEphemeris>,
        val synthesizer: GpsL1RawMeasurementSynthesizer,
    ) {
        @Volatile var receiver: GpsL1ReceiverState? = null
        @Volatile var quality: Map<Int, GpsL1SignalQuality> = emptyMap()
    }

    private val tracks = LinkedHashMap<GnssFrameRoute, Track>()
    private val lock = Any()

    fun event(
        route: GnssFrameRoute,
        publication: PublishedGnssFrame,
        context: ProviderReadContext,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): GnssMeasurementsEvent? {
        val frame = publication.frame
        val time = RawGnssSynthesis.gpsTime(frame.generatedAtMillis) ?: return null
        val gpsSats = frame.satellites
            .filter { it.constellation == Constellation.GPS }
            .associateBy { it.svid }
        if (gpsSats.size < GpsL1RawMeasurementSynthesizer.MIN_MEASUREMENTS) return null
        val available = RawGnssSynthesis.ephemerides(
            context.state.configuration.gnssNavigation,
            frame.generatedAtMillis,
        ).filter { it.svid in gpsSats }
        if (available.size < GpsL1RawMeasurementSynthesizer.MIN_MEASUREMENTS) return null
        val track = synchronized(lock) {
            val current = tracks[route]
            if (current != null && current.ephemerides == available) current
            else Track(available, GpsL1RawMeasurementSynthesizer(
                available,
                receiverAt = { currentTrack(route)?.receiver },
                qualityAt = { ephemeris, _ -> currentTrack(route)?.quality?.get(ephemeris.svid) },
            )).also { tracks[route] = it }
        }
        val clockBias = 1.5e-4
        track.receiver = GpsL1ReceiverState(
            observer = frame.observer,
            ellipsoidHeightMeters = frame.altitudeMeters,
            clockBiasSeconds = clockBias,
            clockDriftSecondsPerSecond = 2e-12,
            ionosphericDelayMeters = 4.0,
            troposphericDelayMeters = 2.5,
            clockUncertaintyNanos = 25.0,
            clockBiasUncertaintyNanos = 8.0,
            clockDriftUncertaintyNanosPerSecond = 0.2,
            elapsedRealtimeUncertaintyNanos = 20.0,
        )
        track.quality = available.associate { ephemeris ->
            val satellite = gpsSats.getValue(ephemeris.svid)
            ephemeris.svid to GpsL1SignalQuality(
                cn0DbHz = satellite.cn0DbHz.toDouble().coerceIn(20.0, 55.0),
                receivedSvTimeUncertaintyNanos = 25L,
                pseudorangeRateUncertaintyMetersPerSecond = 0.2,
                accumulatedDeltaRangeUncertaintyMeters = 0.03,
            )
        }
        val batch = track.synthesizer.synthesize(
            receptionTime = time,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            capturedAtMillis = frame.generatedAtMillis,
            hardwareClockDiscontinuityCount = 0,
        ) ?: return null
        return RawGnssAndroidEvent.create(batch)
    }

    fun clear() = synchronized(lock) { tracks.clear() }

    private fun currentTrack(route: GnssFrameRoute): Track? = synchronized(lock) { tracks[route] }
}
