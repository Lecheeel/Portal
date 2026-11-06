package com.system.location.service.hook.hooks.nmea

import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssFrame
import com.system.location.service.hook.hooks.gnss.GnssStatusBridge
import com.system.location.service.hook.utils.FakeLoc
import moe.microbios.nmea.NmeaConstellation
import moe.microbios.nmea.NmeaFix
import moe.microbios.nmea.NmeaGenerator
import moe.microbios.nmea.NmeaSatellite
import java.util.concurrent.ConcurrentHashMap

/**
 * P3-04: one NMEA source for the whole system.
 *
 * Incoming sentences are replaced by sentences generated from the same `CatalogGnssSkyModel` frame
 * that feeds GnssStatus. Replacement is one-to-one per talker/type so the callback contract is kept;
 * unrepresentable types are suppressed rather than leaking the real sentence.
 */
object NmeaBridge {
    private val lock = Any()
    private var cachedAtMillis = 0L
    private var cachedFrameKey: String? = null
    private var cachedSentences: List<String> = emptyList()

    fun rewrite(incoming: String, counters: ConcurrentHashMap<String, Int>, point: com.system.location.service.core.geo.Wgs84? = null): String {
        if (!FakeLoc.enable) return incoming
        if (!FakeLoc.enableNMEA) return ""
        val sentenceId = sentenceId(incoming) ?: return ""
        val type = sentenceId.substring(2)
        val candidates = generatedSentences(point).filter { candidate ->
            val id = sentenceId(candidate)
            id != null && id == sentenceId
        }.ifEmpty {
            generatedSentences(point).filter { candidate ->
                val id = sentenceId(candidate)
                id != null && id.substring(2) == type
            }
        }
        if (candidates.isEmpty()) return ""
        val index = counters.merge(sentenceId, 1, Int::plus)!! - 1
        return candidates[index % candidates.size].trim()
    }

    fun generatedSentences(point: com.system.location.service.core.geo.Wgs84? = null): List<String> {
        val frame = GnssStatusBridge.currentFrame(point)
        val key = "${frame.generatedAtMillis / 500}:${point?.latitude ?: FakeLoc.latitude}:${point?.longitude ?: FakeLoc.longitude}:${FakeLoc.speed}:${FakeLoc.bearing}"
        synchronized(lock) {
            if (cachedFrameKey == key && System.currentTimeMillis() - cachedAtMillis < 500) {
                return cachedSentences
            }
            val generated = runCatching { NmeaGenerator.generate(toFix(frame, point)) }.getOrDefault(emptyList())
            cachedFrameKey = key
            cachedAtMillis = System.currentTimeMillis()
            cachedSentences = generated
            return generated
        }
    }

    private fun toFix(frame: GnssFrame, point: com.system.location.service.core.geo.Wgs84? = null): NmeaFix {
        val satellites = frame.satellites.mapNotNull { satellite ->
            val constellation = satellite.constellation.toNmea() ?: return@mapNotNull null
            NmeaSatellite(
                constellation = constellation,
                prn = satellite.svid,
                elevationDeg = satellite.elevationDeg,
                azimuthDeg = satellite.azimuthDeg,
                cn0DbHz = satellite.cn0DbHz,
                usedInFix = satellite.usedInFix,
            )
        }
        check(satellites.isNotEmpty()) { "GNSS frame has no NMEA-supported satellites" }
        val bearing = if (point != null) 0.0 else ((FakeLoc.bearing % 360.0) + 360.0) % 360.0
        return NmeaFix(
            latitude = point?.latitude ?: FakeLoc.latitude,
            longitude = point?.longitude ?: FakeLoc.longitude,
            altitudeMeters = FakeLoc.altitude,
            speedMps = if (point != null) 0.0 else FakeLoc.speed.coerceAtLeast(0.0),
            bearingDeg = bearing,
            timeMillis = System.currentTimeMillis(),
            satellites = satellites,
            hdop = frame.hdop.toDouble(),
            vdop = frame.vdop.toDouble(),
            pdop = frame.pdop.toDouble(),
        )
    }

    private fun sentenceId(sentence: String): String? {
        val trimmed = sentence.trim()
        if (!trimmed.startsWith("$")) return null
        val id = trimmed.removePrefix("$").substringBefore(',').substringBefore('*')
        return id.takeIf { it.length >= 5 && it.take(2).all(Char::isLetter) && it.drop(2).all(Char::isLetter) }
    }

    private fun Constellation.toNmea(): NmeaConstellation? = when (this) {
        Constellation.GPS -> NmeaConstellation.GPS
        Constellation.GLONASS -> NmeaConstellation.GLONASS
        Constellation.GALILEO -> NmeaConstellation.GALILEO
        Constellation.BEIDOU -> NmeaConstellation.BEIDOU
        Constellation.QZSS, Constellation.SBAS, Constellation.IRNSS -> null
    }
}
