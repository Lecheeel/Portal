package com.system.location.service.ext

import android.content.Context
import com.system.location.service.data.repository.*
import androidx.core.content.edit
import com.amap.api.maps.AMap
import com.system.location.service.service.MockServiceHelper
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.mock.HistoricalRoute
import com.system.location.service.ui.mock.RouteJson
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.data.persistence.CoordinatePreferences

val Context.sharedPrefs
    get() = getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)!!

private val Context.coordinatePreferences: CoordinatePreferences
    get() = CoordinatePreferences({ sharedPrefs.all }) { values ->
        sharedPrefs.edit {
            values.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }
    }

var Context.lastKnownLat: Double
    get() = coordinatePreferences.get(CoordinatePreferences.LATITUDE)
    set(value) = coordinatePreferences.set(CoordinatePreferences.LATITUDE, value)

var Context.lastKnownLng: Double
    get() = coordinatePreferences.get(CoordinatePreferences.LONGITUDE)
    set(value) = coordinatePreferences.set(CoordinatePreferences.LONGITUDE, value)

var Context.selectLocation: HistoricalLocation?
    get() {
        return sharedPrefs.getString("selectedLocation", null)?.let {
            runCatching { HistoricalLocation.fromString(it) }.getOrNull()
        }
    }
    set(value) = sharedPrefs.edit {
        putString("selectedLocation", value?.toString())
    }

var Context.selectRoute: HistoricalRoute?
    get() {
        return sharedPrefs.getString("selectedRoute", null)?.let {
            try {
                RouteJson.decodeRoute(it)
            } catch (e: Exception) {
                sharedPrefs.edit {
                    remove("selectedRoute")
                }
                null
            }
        }
    }
    set(value) = sharedPrefs.edit {
        putString("selectedRoute", value?.let(RouteJson::encodeRoute))
    }

val Context.historicalLocations: List<HistoricalLocation>
    get() {
        LibraryRepositories.ensureMigrated()
        return LibraryRepositories.locations.list().map { it.toHistorical() }
    }

var Context.rawHistoricalLocations: Set<String>
    get() = historicalLocations.map { it.toString() }.toSet()
    set(value) {
        LibraryRepositories.ensureMigrated()
        LibraryRepositories.locations.replaceAll(value.map { HistoricalLocation.fromString(it).toSaved() })
    }

var Context.jsonHistoricalRoutes: String
    get() {
        LibraryRepositories.ensureMigrated()
        return RouteJson.encodeRoutes(LibraryRepositories.routes.list().map { it.toHistorical() })
    }
    set(value) {
        LibraryRepositories.ensureMigrated()
        LibraryRepositories.routes.replaceAll(RouteJson.decodeRoutes(value).map { it.toSaved() })
    }

var Context.reportDuration: Int
    get() = sharedPrefs.getInt("reportDuration", 100).coerceIn(50, 1000)
    set(value) = sharedPrefs.edit {
        putInt("reportDuration", value)
    }

var Context.experimentalClearMockFlag: Boolean
    get() = sharedPrefs.getBoolean("experimentalClearMockFlag", false)
    set(value) = sharedPrefs.edit { putBoolean("experimentalClearMockFlag", value) }

var Context.minSatelliteCount: Int
    get() = sharedPrefs.getInt("minSatelliteCount", 12)
    set(value) = sharedPrefs.edit {
        putInt("minSatelliteCount", value)
    }

var Context.mapType: Int
    get() = sharedPrefs.getInt("mapType", AMap.MAP_TYPE_NORMAL)
    set(value) = sharedPrefs.edit {
        putInt("mapType", value)
    }

var Context.rockerCoords: Pair<Int, Int>
    get() {
        val x = sharedPrefs.getInt("rocker_x", 0)
        val y = sharedPrefs.getInt("rocker_y", 0)
        return Pair(x, y)
    }
    set(value) = sharedPrefs.edit {
        putInt("rocker_x", value.first)
        putInt("rocker_y", value.second)
    }

var Context.speed: Double
    get() = sharedPrefs.getFloat("speed", FakeLoc.speed.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("speed", value.toFloat())
    }

var Context.altitude: Double
    get() = sharedPrefs.getFloat("altitude", FakeLoc.altitude.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("altitude", value.toFloat())
    }

var Context.accuracy: Float
    get() = sharedPrefs.getFloat("accuracy", FakeLoc.accuracy)
    set(value) = sharedPrefs.edit {
        putFloat("accuracy", value)
    }

var Context.needOpenSELinux: Boolean
    get() = sharedPrefs.getBoolean("needOpenSELinux", false)
    set(value) = sharedPrefs.edit {
        putBoolean("needOpenSELinux", value)
    }

var Context.needDowngradeToCdma: Boolean
    get() = sharedPrefs.getBoolean("needDowngradeToCdma", FakeLoc.needDowngradeToCdma)
    set(value) = sharedPrefs.edit {
        putBoolean("needDowngradeToCdma", value)
    }

var Context.hookSensor: Boolean
    get() = sharedPrefs.getBoolean("hookSensor", false)
    set(value) = sharedPrefs.edit {
        putBoolean("hookSensor", value)
    }

var Context.debug: Boolean
    get() = sharedPrefs.getBoolean("debug", FakeLoc.enableDebugLog)
    set(value) = sharedPrefs.edit {
        putBoolean("debug", value)
    }

var Context.disableGetCurrentLocation: Boolean
    get() = sharedPrefs.getBoolean("disableGetCurrentLocation", FakeLoc.disableGetCurrentLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableGetCurrentLocation", value)
    }

var Context.disableRegisterLocationListener: Boolean
    get() = sharedPrefs.getBoolean(
        "disableRegitserLocationListener",
        FakeLoc.disableRegisterLocationListener
    )
    set(value) = sharedPrefs.edit {
        putBoolean("disableRegitserLocationListener", value)
    }

var Context.disableFusedProvider: Boolean
    get() = sharedPrefs.getBoolean("disableFusedProvider", FakeLoc.disableFusedLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableFusedProvider", value)
        FakeLoc.disableFusedLocation = value
    }

var Context.enableRequestGeofence: Boolean
    get() = sharedPrefs.getBoolean("enableRequestGeofence", !FakeLoc.disableRequestGeofence)
    set(value) = sharedPrefs.edit {
        putBoolean("enableRequestGeofence", value)
        FakeLoc.disableRequestGeofence = !value
    }

var Context.enableGetFromLocation: Boolean
    get() = sharedPrefs.getBoolean("enableGetFromLocation", !FakeLoc.disableGetFromLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("enableGetFromLocation", value)
        FakeLoc.disableGetFromLocation = !value
    }

var Context.enableAGPS: Boolean
    get() = sharedPrefs.getBoolean("enableAGPS", FakeLoc.enableAGPS)
    set(value) = sharedPrefs.edit {
        putBoolean("enableAGPS", value)
        FakeLoc.enableAGPS = value
    }

var Context.enableNMEA: Boolean
    get() = sharedPrefs.getBoolean("enableNMEA", FakeLoc.enableNMEA)
    set(value) = sharedPrefs.edit {
        putBoolean("enableNMEA", value)
        FakeLoc.enableNMEA = value
    }

var Context.disableWifiScan: Boolean
    get() = sharedPrefs.getBoolean("disableWifiScan", FakeLoc.enableMockWifi)
    set(value) = sharedPrefs.edit {
        putBoolean("disableWifiScan", value)
        FakeLoc.enableMockWifi = value
    }

var Context.loopBroadcastlocation: Boolean
    get() = sharedPrefs.getBoolean("loopBroadcastLocation", FakeLoc.loopBroadcastLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("loopBroadcastLocation", value)
        FakeLoc.loopBroadcastLocation = value
    }
