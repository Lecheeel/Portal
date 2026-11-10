package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

/** Coordinate realization required by Galileo geometry; no WGS-84 to GTRF conversion is guessed. */
@Serializable
enum class GalileoReceiverFrame { GTRF }

/**
 * Compact Galileo orbit evidence that is safe to move across the app/system boundary.
 * The GPS elapsed clock and independently decoded GGTO keep GST in its own time domain;
 * no UTC or zero-offset assumption is allowed here.
 */
@Serializable
data class GalileoBroadcastEvidence(
    val clock: GpsElapsedClockEvidence,
    val gpsToGst: GalileoGpsTimeOffset,
    val receiverFrame: GalileoReceiverFrame,
    val inav: List<DecodedGalileoInav> = emptyList(),
    val fnav: List<DecodedGalileoFnav> = emptyList(),
) {
    init {
        require(inav.isNotEmpty() || fnav.isNotEmpty()) { "Galileo evidence is empty" }
        val identities = (inav.map { it.ephemeris.svid } + fnav.map { it.ephemeris.svid })
        require(identities.distinct().size == identities.size) { "duplicate Galileo evidence identity" }
        require((inav.map { it.ephemeris.svid } + fnav.map { it.ephemeris.svid }).all { it in 1..36 }) {
            "invalid Galileo evidence SVID"
        }
    }

    val identities: Set<Int>
        get() = (inav.map { it.ephemeris.svid } + fnav.map { it.ephemeris.svid }).toSet()

    companion object {
        /**
         * Decode only when an independently established GGTO is supplied. A missing offset
         * returns null instead of treating GPS and GST as the same clock.
         */
        fun fromCapture(capture: GnssCapture, gpsToGst: GalileoGpsTimeOffset?,
            receiverFrame: GalileoReceiverFrame? = null): GalileoBroadcastEvidence? {
            val offset = gpsToGst ?: return null
            val frame = receiverFrame ?: return null
            val clock = GpsElapsedClockEvidence.fromCapture(capture) ?: return null
            val visible = capture.frames.flatMap { it.satellites }.filter {
                it.constellation == Constellation.GALILEO && it.elevationDeg >= 0f &&
                    it.azimuthDeg in 0f..<360f && it.cn0DbHz > 0f
            }.map { it.svid }.toSet()
            if (visible.isEmpty()) return null
            val decoded = GalileoCaptureEphemerisDecoder.decode(capture, offset)
            val inav = decoded.inav.filter { it.ephemeris.svid in visible }
            val fnav = decoded.fnav.filter { it.ephemeris.svid in visible }
            return if (inav.isEmpty() && fnav.isEmpty()) null else GalileoBroadcastEvidence(clock, offset, frame, inav, fnav)
        }
    }
}
