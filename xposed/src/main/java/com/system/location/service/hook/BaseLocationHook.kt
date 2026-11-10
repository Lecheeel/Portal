package com.system.location.service.hook

import com.system.location.service.hook.utils.toLocation

import android.location.Location
import android.location.LocationManager
import com.system.location.service.core.location.LocationSample
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.hook.utils.ProviderReadContext
import com.system.location.service.core.gnss.GnssFrameRoute
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue

abstract class BaseLocationHook: BaseDivineService() {
    /** Provider-specific view of the current fake fix (P4-03): accuracy, latency, field mask. */
    fun sampleForProvider(
        provider: String?,
        scenarioId: String? = null,
        point: com.system.location.service.core.geo.Wgs84? = null,
        context: ProviderReadContext = ProviderView.capture(),
    ): LocationSample = ProviderView.sampleFor(provider, scenarioId, point, context)

    fun locationForProvider(provider: String?, scenarioId: String? = null,
        point: com.system.location.service.core.geo.Wgs84? = null,
        context: ProviderReadContext = ProviderView.capture()): Location =
        sampleForProvider(provider, scenarioId, point, context).toLocation(provider,
            GnssFrameRoute.forObserver(scenarioId, point), context.state, context.validationAtMillis)

    fun injectLocation(originLocation: Location, realLocation: Boolean = true,
        context: ProviderReadContext? = null): Location {
        if (!(context?.state ?: FakeLoc.runtimeSnapshots.current()).configuration.enabled) {
            if (realLocation && originLocation.provider == LocationManager.GPS_PROVIDER) {
                FakeLoc.lastLocation = originLocation
            }
            return originLocation
        }
        val captured = context ?: ProviderView.capture()
        if (!captured.state.configuration.enabled) return originLocation
        val provider = if (captured.state.configuration.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            LocationManager.GPS_PROVIDER
        } else originLocation.provider
        return locationForProvider(provider, context = captured)
    }

    fun injectNMEA(nmeaStr: String): String? {
        if (!FakeLoc.enable) {
            return null
        }

        kotlin.runCatching {
            val nmea = NMEA.valueOf(nmeaStr)
            when(val value = nmea.value) {
                is NmeaValue.DTM -> {
                    return null
                }
                is NmeaValue.GGA -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.fixQuality == 0) {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.GNS -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.mode == "N") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.GSA -> {
                    return null
                }
                is NmeaValue.GSV -> {
                    return null
                }
                is NmeaValue.RMC -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.status == "V") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.VTG -> {
                    return null
                }
            }
        }.onFailure {
            Logger.error("NMEA parse failed: ${it.message}, source = $nmeaStr")
            return null
        }
        return null
    }
}
