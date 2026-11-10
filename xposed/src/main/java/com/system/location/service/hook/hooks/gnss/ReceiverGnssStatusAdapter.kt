package com.system.location.service.hook.hooks.gnss

import android.location.GnssStatus
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.PublishedGnssFrame
import java.lang.reflect.Method

/** Public GnssStatus.Builder covers evolving Android parcel layouts; legacy arrays stay isolated. */
object ReceiverGnssStatusAdapter {
    fun supports(method: Method): Boolean {
        val types = method.parameterTypes.toList()
        if (types.size == 1 && types.single().name == "android.location.GnssStatus") return true
        return types.size in 5..7 && types[0] == Int::class.javaPrimitiveType &&
            types[1] == IntArray::class.java && types.drop(2).all { it == FloatArray::class.java }
    }

    fun arguments(method: Method, publication: PublishedGnssFrame): Array<Any?> {
        require(supports(method)) { "unsupported GNSS status callback signature" }
        if (method.parameterCount == 1) {
            val builder = GnssStatus.Builder()
            publication.frame.satellites.forEach { satellite ->
                builder.addSatellite(code(satellite.constellation), satellite.svid, satellite.cn0DbHz,
                    satellite.elevationDeg, satellite.azimuthDeg, satellite.hasEphemeris, satellite.hasAlmanac,
                    satellite.usedInFix, satellite.carrierHz != null, satellite.carrierHz ?: 0f, false, 0f)
            }
            return arrayOf(builder.build())
        }
        val satellites = publication.frame.satellites
        val flags = IntArray(satellites.size) { index ->
            val satellite = satellites[index]
            var bits = 0
            if (satellite.hasEphemeris) bits = bits or 1
            if (satellite.hasAlmanac) bits = bits or 2
            if (satellite.usedInFix) bits = bits or 4
            if (satellite.carrierHz != null) bits = bits or 8
            (satellite.svid shl 12) or (code(satellite.constellation) shl 8) or bits
        }
        // Baseband CN0 was not observed. The flag stays unset and the unused array contains zeroes.
        val args: Array<Any?> = arrayOf(satellites.size, flags,
            satellites.map { it.cn0DbHz }.toFloatArray(), satellites.map { it.elevationDeg }.toFloatArray(),
            satellites.map { it.azimuthDeg }.toFloatArray(), satellites.map { it.carrierHz ?: 0f }.toFloatArray(),
            FloatArray(satellites.size))
        return args.copyOf(method.parameterCount)
    }

    private fun code(system: Constellation): Int = when (system) {
        Constellation.GPS -> 1
        Constellation.SBAS -> 2
        Constellation.GLONASS -> 3
        Constellation.QZSS -> 4
        Constellation.BEIDOU -> 5
        Constellation.GALILEO -> 6
        Constellation.IRNSS -> 7
    }
}
