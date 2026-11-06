package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.CatalogGnssSkyModel
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssFrame
import com.system.location.service.hook.utils.FakeLoc

/** Shared GNSS catalog -> legacy GnssStatus array bridge (P3-01/P3-04). */
object GnssStatusBridge {
    private val model = CatalogGnssSkyModel()

    fun currentFrame(): GnssFrame = model.frame(
        atMillis = System.currentTimeMillis(),
        observer = Wgs84(FakeLoc.latitude, FakeLoc.longitude),
        altitudeMeters = FakeLoc.altitude,
    )

    fun toMockData(frame: GnssFrame): MockGnssData {
        val count = frame.satellites.size
        val svidWithFlags = IntArray(count)
        val cn0s = FloatArray(count)
        val elevations = FloatArray(count)
        val azimuths = FloatArray(count)
        val carrierFreqs = FloatArray(count)
        frame.satellites.forEachIndexed { index, satellite ->
            var flags = GnssFlags.SVID_FLAGS_NONE
            if (satellite.hasEphemeris) flags = flags or GnssFlags.SVID_FLAGS_HAS_EPHEMERIS_DATA
            if (satellite.hasAlmanac) flags = flags or GnssFlags.SVID_FLAGS_HAS_ALMANAC_DATA
            if (satellite.usedInFix) flags = flags or GnssFlags.SVID_FLAGS_USED_IN_FIX
            if (satellite.carrierHz != null) flags = flags or GnssFlags.SVID_FLAGS_HAS_CARRIER_FREQUENCY
            flags = flags or GnssFlags.SVID_FLAGS_HAS_BASEBAND_CN0
            svidWithFlags[index] = (satellite.svid shl GnssFlags.SVID_SHIFT_WIDTH) or
                ((constellationCode(satellite.constellation) and GnssFlags.CONSTELLATION_TYPE_MASK) shl
                    GnssFlags.CONSTELLATION_TYPE_SHIFT_WIDTH) or flags
            cn0s[index] = satellite.cn0DbHz
            elevations[index] = satellite.elevationDeg
            azimuths[index] = satellite.azimuthDeg
            carrierFreqs[index] = satellite.carrierHz ?: 0f
        }
        return MockGnssData(count, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs)
    }

    fun basebandCn0(data: MockGnssData): FloatArray =
        FloatArray(data.svCount) { (data.cn0s[it] - 2f).coerceAtLeast(0f) }

    private fun constellationCode(constellation: Constellation): Int = when (constellation) {
        Constellation.GPS -> GnssFlags.CONSTELLATION_GPS
        Constellation.SBAS -> GnssFlags.CONSTELLATION_SBAS
        Constellation.GLONASS -> GnssFlags.CONSTELLATION_GLONASS
        Constellation.QZSS -> GnssFlags.CONSTELLATION_QZSS
        Constellation.BEIDOU -> GnssFlags.CONSTELLATION_BEIDOU
        Constellation.GALILEO -> GnssFlags.CONSTELLATION_GALILEO
        Constellation.IRNSS -> GnssFlags.CONSTELLATION_IRNSS
    }
}

data class MockGnssData(
    val svCount: Int,
    val svidWithFlags: IntArray,
    val cn0s: FloatArray,
    val elevations: FloatArray,
    val azimuths: FloatArray,
    val carrierFreqs: FloatArray,
)

object GnssFlags {
    const val SVID_FLAGS_NONE = 0
    const val SVID_FLAGS_HAS_EPHEMERIS_DATA = (1 shl 0)
    const val SVID_FLAGS_HAS_ALMANAC_DATA = (1 shl 1)
    const val SVID_FLAGS_USED_IN_FIX = (1 shl 2)
    const val SVID_FLAGS_HAS_CARRIER_FREQUENCY = (1 shl 3)
    const val SVID_FLAGS_HAS_BASEBAND_CN0 = (1 shl 4)

    const val SVID_SHIFT_WIDTH = 12
    const val CONSTELLATION_TYPE_SHIFT_WIDTH = 8
    const val CONSTELLATION_TYPE_MASK = 0xf

    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7
}
