package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

/**
 * Independently established applicability of a decoded Galileo health/DVS/SISA/BGD snapshot.
 * The exact word-5/page-1 epoch and format identify the snapshot. Neither the orbital age
 * limit nor the GGTO/receiver-transform lifetime establishes this service interval.
 */
@Serializable
data class GalileoServiceValidity(
    val svid: Int,
    val format: GalileoClockReference,
    val broadcastAt: GalileoTime,
    val from: GalileoTime,
    val until: GalileoTime,
) {
    init {
        require(svid in 1..36)
        require(broadcastAt.secondsSince(from) >= 0.0 && until.secondsSince(broadcastAt) > 0.0)
    }

    /** End-exclusive; these are full GST epochs, never modulo-week ages. */
    fun contains(transmission: GalileoTime): Boolean =
        transmission.secondsSince(from) >= 0.0 && until.secondsSince(transmission) > 0.0
}
