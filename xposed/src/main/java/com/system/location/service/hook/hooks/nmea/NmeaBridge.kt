package com.system.location.service.hook.hooks.nmea

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssSatelliteIds
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.hook.hooks.gnss.GnssReceiverRuntime
import moe.microbios.nmea.NmeaConstellation
import moe.microbios.nmea.NmeaFix
import moe.microbios.nmea.NmeaGenerator
import moe.microbios.nmea.NmeaSatellite

/** Output from the exact shared publication. No secondary model, wall-clock read or hidden catch. */
object NmeaBridge {
    fun generatedSentences(point: Wgs84? = null): List<String> =
        GnssReceiverRuntime.currentFrame(point)?.let(::generatedSentences).orEmpty()

    fun generatedSentences(publication: PublishedGnssFrame): List<String> {
        val frame = publication.frame
        val dop = frame.geometry.dop
        val satellites = frame.satellites.map { satellite ->
            val system = when (satellite.constellation) {
                Constellation.GPS, Constellation.SBAS -> NmeaConstellation.GPS
                Constellation.GLONASS -> NmeaConstellation.GLONASS
                Constellation.BEIDOU -> NmeaConstellation.BEIDOU
                Constellation.GALILEO -> NmeaConstellation.GALILEO
                Constellation.QZSS -> NmeaConstellation.QZSS
                Constellation.IRNSS -> NmeaConstellation.IRNSS
            }
            NmeaSatellite(system, checkNotNull(GnssSatelliteIds.nmeaId(satellite.constellation, satellite.svid)),
                satellite.elevationDeg, satellite.azimuthDeg, satellite.cn0DbHz, satellite.usedInFix)
        }
        return NmeaGenerator.generate(NmeaFix(latitude = frame.observer.latitude,
            longitude = frame.observer.longitude, altitudeMeters = frame.altitudeMeters,
            speedMps = publication.speedMps, bearingDeg = publication.bearingDeg,
            timeMillis = frame.generatedAtMillis, satellites = satellites,
            hdop = dop?.hdop, vdop = dop?.vdop, pdop = dop?.pdop))
    }
}
