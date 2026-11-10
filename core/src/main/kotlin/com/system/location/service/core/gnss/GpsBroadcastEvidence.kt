package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

/** Compact decoded evidence for authenticated IPC; not raw measurements or a live health service. */
@Serializable
data class GpsBroadcastEvidence(
    val clock: GpsElapsedClockEvidence,
    val ephemerides: List<DecodedGpsLnav>,
) {
    init {
        require(ephemerides.isNotEmpty() && ephemerides.size <= 32)
        require(ephemerides.map { it.ephemeris.svid }.distinct().size == ephemerides.size)
        val anchor = GpsTime(clock.gpsWeek, clock.gpsSecondsOfWeek)
        require(ephemerides.all {
            // Three CEI subframes end 18 seconds after the frame's transmission start.
            anchor.secondsSince(it.frameTransmissionTime.plusSeconds(18.0)) in
                -GpsElapsedClockEvidence.MAX_ANCHOR_UNCERTAINTY_SECONDS..MAX_BROADCAST_AGE_SECONDS
        }) { "broadcast evidence is not contemporaneous with receiver clock" }
    }

    companion object {
        // Simulation evidence freshness policy, not orbit fit or a guarantee of future health.
        const val MAX_BROADCAST_AGE_SECONDS = 1800.0

        fun fromCapture(capture: GnssCapture): GpsBroadcastEvidence? {
            val clock = GpsElapsedClockEvidence.fromCapture(capture) ?: return null
            // A navigation message alone does not prove that the receiver observed the identity
            // as visible. Only valid above-horizon status samples may authorize physical output.
            val observed = visibleGpsIdentities(capture.frames)
            val anchor = GpsTime(clock.gpsWeek, clock.gpsSecondsOfWeek)
            val decoded = GpsCaptureEphemerisDecoder.decode(capture).decoded.filter {
                it.ephemeris.svid in observed && anchor.secondsSince(it.frameTransmissionTime.plusSeconds(18.0)) in
                    -GpsElapsedClockEvidence.MAX_ANCHOR_UNCERTAINTY_SECONDS..MAX_BROADCAST_AGE_SECONDS
            }
            return if (decoded.isEmpty()) null else GpsBroadcastEvidence(clock, decoded)
        }

        internal fun visibleGpsIdentities(frames: List<GnssObservationFrame>): Set<Int> =
            frames.flatMap { it.satellites }.filter {
                it.constellation == Constellation.GPS && GnssCaptureValidator.isUsableSatellite(it) &&
                    (it.carrierHz == null || it.carrierHz.isFinite() && it.carrierHz > 0f)
            }.map { it.svid }.toSet()
    }
}
