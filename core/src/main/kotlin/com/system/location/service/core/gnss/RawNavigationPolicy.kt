package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

enum class RawNavigationType { UNKNOWN, GPS_LNAV, GPS_CNAV, BDS_D1, BDS_D2, GAL_F, GAL_I, GLO }

enum class RawNavigationStatus { UNKNOWN, PARITY_PASSED, PARITY_REBUILT }

@Serializable
data class RawNavigationMessage(
    val constellation: Constellation,
    val svid: Int,
    val type: RawNavigationType,
    val status: RawNavigationStatus,
    val data: List<Int>,
    val receivedSvTimeNanos: Long,
) {
    init {
        require(svid in 1..999) { "svid out of range: $svid" }
        require(data.size in 1..MAX_DATA_BYTES) { "navigation data size out of range" }
        require(data.all { it in 0..255 }) { "navigation data must be bytes" }
        require(receivedSvTimeNanos >= 0L) { "receivedSvTimeNanos must be non-negative" }
    }

    companion object {
        const val MAX_DATA_BYTES = 64
    }
}

/**
 * P3-05/P7-01 navigation-message spike: OFF by default; EXPERIMENTAL derives deterministic 24-byte
 * messages from the same sky frame. Not wired into GnssHook/AntennaInfo yet.
 */
data class RawNavigationPolicy(
    val mode: RawGnssMode = RawGnssMode.OFF,
    val maxMessages: Int = MAX_MESSAGES,
) {
    init {
        require(maxMessages in 1..MAX_MESSAGES) { "maxMessages out of range: $maxMessages" }
    }

    fun messages(frame: GnssFrame, timeNanos: Long): List<RawNavigationMessage> {
        require(timeNanos >= 0L) { "timeNanos must be non-negative" }
        if (mode == RawGnssMode.OFF) return emptyList()
        return frame.usedInFix
            .asSequence()
            .take(maxMessages)
            .map { satellite ->
                RawNavigationMessage(
                    constellation = satellite.constellation,
                    svid = satellite.svid,
                    type = navigationType(satellite.constellation),
                    status = RawNavigationStatus.PARITY_PASSED,
                    data = List(DATA_BYTES) { index ->
                        (satellite.svid * 31 + index * 17 + (timeNanos / 1_000_000L).toInt()) and 0xFF
                    },
                    receivedSvTimeNanos = timeNanos,
                )
            }
            .toList()
    }

    private fun navigationType(constellation: Constellation): RawNavigationType = when (constellation) {
        Constellation.GPS -> RawNavigationType.GPS_LNAV
        Constellation.BEIDOU -> RawNavigationType.BDS_D1
        Constellation.GALILEO -> RawNavigationType.GAL_F
        Constellation.GLONASS -> RawNavigationType.GLO
        Constellation.QZSS -> RawNavigationType.GPS_LNAV
        Constellation.SBAS, Constellation.IRNSS -> RawNavigationType.UNKNOWN
    }

    companion object {
        const val MAX_MESSAGES = 32
        const val DATA_BYTES = 24
    }
}
