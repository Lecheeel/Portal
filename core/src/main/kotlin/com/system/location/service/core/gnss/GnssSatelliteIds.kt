package com.system.location.service.core.gnss

/**
 * Android SVID -> NMEA local talker ID. All adapters consume the same supported subset.
 * Sources: Android GnssStatus.getSvid and https://docs.novatel.com/OEM7/Content/Logs/GPGSV.htm
 * GLONASS FCN-only IDs and extended SBAS/QZSS IDs have no unambiguous mapping in this format;
 * keep them in raw evidence but exclude them from every generated output with a diagnostic.
 */
object GnssSatelliteIds {
    fun nmeaId(constellation: Constellation, svid: Int): Int? = when (constellation) {
        Constellation.GPS -> svid.takeIf { it in 1..32 }
        Constellation.GLONASS -> svid.takeIf { it in 1..25 }?.plus(64)
        Constellation.BEIDOU -> svid.takeIf { it in 1..63 }
        Constellation.GALILEO -> svid.takeIf { it in 1..36 }
        Constellation.QZSS -> svid.takeIf { it in 193..202 }?.minus(192)
        Constellation.IRNSS -> svid.takeIf { it in 1..14 }
        Constellation.SBAS -> svid.takeIf { it in 120..151 }?.minus(87)
    }
}
