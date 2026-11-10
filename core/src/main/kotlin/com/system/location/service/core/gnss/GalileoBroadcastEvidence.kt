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
    /** Frame names alone cannot convert a WGS 84 route into GTRF. */
    val receiverTransform: GnssReceiverDatumTransform? = null,
    val serviceValidity: List<GalileoServiceValidity> = emptyList(),
) {
    init {
        require(receiverTransform == null || receiverTransform.bootCount == clock.bootCount)
        require(inav.isNotEmpty() || fnav.isNotEmpty()) { "Galileo evidence is empty" }
        require(inav.map { it.ephemeris.svid }.distinct().size == inav.size) { "duplicate Galileo I/NAV identity" }
        require(fnav.map { it.ephemeris.svid }.distinct().size == fnav.size) { "duplicate Galileo F/NAV identity" }
        require((inav.map { it.ephemeris.svid } + fnav.map { it.ephemeris.svid }).all { it in 1..36 }) {
            "invalid Galileo evidence SVID"
        }
        require(serviceValidity.size <= 72)
        require(serviceValidity.map { it.svid to it.format }.distinct().size == serviceValidity.size)
        require(serviceValidity.all { service ->
            when (service.format) {
                GalileoClockReference.INAV_E1_E5B -> inav.any {
                    it.ephemeris.svid == service.svid && it.transmissionTimeOfWordFive == service.broadcastAt
                }
                GalileoClockReference.FNAV_E1_E5A -> fnav.any {
                    it.ephemeris.svid == service.svid && it.transmissionTimeOfPageOne == service.broadcastAt
                }
            }
        }) { "Galileo service applicability is not bound to the decoded status snapshot" }
    }

    val identities: Set<Int>
        get() = (inav.map { it.ephemeris.svid } + fnav.map { it.ephemeris.svid }).toSet()

    fun serviceRejection(svid: Int, format: GalileoClockReference, broadcastAt: GalileoTime,
        transmission: GalileoTime): String? {
        val service = serviceValidity.singleOrNull { it.svid == svid && it.format == format && it.broadcastAt == broadcastAt }
            ?: return "缺少与当前健康广播绑定的独立服务适用期"
        return if (service.contains(transmission)) null else "发射时刻超出独立服务适用期"
    }

    companion object {
        /**
         * Decode only when an independently established GGTO is supplied. A missing offset
         * returns null instead of treating GPS and GST as the same clock.
         */
        fun fromCapture(capture: GnssCapture, gpsToGst: GalileoGpsTimeOffset?,
            receiverFrame: GalileoReceiverFrame? = null,
            receiverTransform: GnssReceiverDatumTransform? = null,
            serviceValidity: List<GalileoServiceValidity> = emptyList()): GalileoBroadcastEvidence? {
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
            return if (inav.isEmpty() && fnav.isEmpty()) null
                else GalileoBroadcastEvidence(clock, offset, frame, inav, fnav, receiverTransform, serviceValidity)
        }
    }
}
