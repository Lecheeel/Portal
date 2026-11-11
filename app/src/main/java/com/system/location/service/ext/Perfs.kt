package com.system.location.service.ext

import android.content.Context
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.gnss.GnssFrame
import com.system.location.service.core.gnss.GnssSimulationPolicy
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
    get() = sharedPrefs.getBoolean("experimentalClearMockFlag", true)
    set(value) = sharedPrefs.edit { putBoolean("experimentalClearMockFlag", value) }

var Context.experimentalOrbitMotion: Boolean
    get() = sharedPrefs.getBoolean("experimentalOrbitMotion", false)
    set(value) = sharedPrefs.edit { putBoolean("experimentalOrbitMotion", value) }

var Context.experimentalOrbitRadius: Double
    get() = sharedPrefs.getFloat("experimentalOrbitRadius", 0.2f).toDouble().coerceIn(0.05, 5.0)
    set(value) = sharedPrefs.edit { putFloat("experimentalOrbitRadius", value.toFloat()) }

var Context.orbitPeriodSeconds: Double
    get() = sharedPrefs.getFloat("orbitPeriodSeconds", 20f).toDouble().coerceIn(5.0, 120.0)
    set(value) = sharedPrefs.edit { putFloat("orbitPeriodSeconds", value.toFloat()) }

var Context.smoothRouteMotion: Boolean
    get() = sharedPrefs.getBoolean("smoothRouteMotion", true)
    set(value) = sharedPrefs.edit { putBoolean("smoothRouteMotion", value) }

var Context.gmsMockEnabled: Boolean
    get() = sharedPrefs.getBoolean("gmsMockEnabled", false)
    set(value) = sharedPrefs.edit { putBoolean("gmsMockEnabled", value) }
var Context.platformFusedEnabled: Boolean
    get() = sharedPrefs.getBoolean("platformFusedEnabled", true)
    set(value) = sharedPrefs.edit { putBoolean("platformFusedEnabled", value) }
var Context.startupBurstEnabled: Boolean
    get() = sharedPrefs.getBoolean("startupBurstEnabled", true)
    set(value) = sharedPrefs.edit { putBoolean("startupBurstEnabled", value) }

fun Context.movementProfile() = com.system.location.service.core.scenario.MovementProfile(
    speed, altitude, accuracy.coerceAtLeast(0.1f), reportDuration.toLong(), smoothRouteMotion)

var Context.minSatelliteCount: Int
    get() = sharedPrefs.getInt("minSatelliteCount", GnssSimulationPolicy.DEFAULT_MINIMUM_SATELLITES).coerceIn(0, GnssFrame.MAX_SATELLITES)
    set(value) = sharedPrefs.edit {
        putInt("minSatelliteCount", value.coerceIn(0, GnssFrame.MAX_SATELLITES))
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
    get() = sharedPrefs.getFloat("altitude", 0f).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("altitude", value.toFloat())
    }

var Context.accuracy: Float
    get() = sharedPrefs.getFloat("accuracy", com.system.location.service.core.location.LocationSanitizer.DEFAULT_HORIZONTAL_ACCURACY)
        .coerceIn(0.1f, 10_000f)
    set(value) = sharedPrefs.edit {
        putFloat("accuracy", value.coerceIn(0.1f, 10_000f))
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

/** Java-side cadence tuning; Native receives the resulting cadence and variation only. */
var Context.sensorTargetPackages: Set<String>
    get() = runCatching { com.system.location.service.core.sensors.SensorTargets.parse(
        sharedPrefs.getString("sensorTargetPackages", "").orEmpty()) }.getOrDefault(emptySet())
    set(value) {
        val validated = com.system.location.service.core.sensors.SensorTargets.parse(value.joinToString(","))
        sharedPrefs.edit { putString("sensorTargetPackages", validated.sorted().joinToString(",")) }
    }

var Context.sensorCadenceScale: Double
    get() = sharedPrefs.getFloat("sensorCadenceScale", 1.0f).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("sensorCadenceScale", value.toFloat().coerceIn(0.5f, 2.0f))
    }

var Context.sensorVariation: Double
    get() = sharedPrefs.getFloat("sensorVariation", 0.15f).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("sensorVariation", value.toFloat().coerceIn(0f, 0.5f))
    }

var Context.debug: Boolean
    get() = sharedPrefs.getBoolean("debug", FakeLoc.enableDebugLog)
    set(value) = sharedPrefs.edit {
        putBoolean("debug", value)
    }

var Context.environmentReplayJson: String
    get() = sharedPrefs.getString("environmentReplayJson", "").orEmpty()
    set(value) = sharedPrefs.edit {
        putString("environmentReplayJson", value)
    }

var Context.physicalNoise: Boolean
    get() = sharedPrefs.getBoolean("physicalNoise", FakeLoc.enablePhysicalNoise)
    set(value) = sharedPrefs.edit {
        putBoolean("physicalNoise", value)
    }

var Context.wifiFingerprintEnabled: Boolean
    get() = sharedPrefs.getBoolean("wifiFingerprintEnabled", false)
    set(value) = sharedPrefs.edit { putBoolean("wifiFingerprintEnabled", value) }

var Context.wifiFingerprintCatalogJson: String
    get() = sharedPrefs.getString("wifiFingerprintCatalogJson", "").orEmpty()
    set(value) = sharedPrefs.edit { putString("wifiFingerprintCatalogJson", value) }

var Context.simReady: Boolean
    get() = sharedPrefs.getBoolean("simReady", true)
    set(value) = sharedPrefs.edit {
        putBoolean("simReady", value)
    }

var Context.environmentApCount: Int
    get() = sharedPrefs.getInt("environmentApCount", 4).coerceIn(EnvironmentConfig.MIN_AP_PER_CELL, EnvironmentConfig.MAX_AP_PER_CELL)
    set(value) = sharedPrefs.edit {
        putInt("environmentApCount", value.coerceIn(EnvironmentConfig.MIN_AP_PER_CELL, EnvironmentConfig.MAX_AP_PER_CELL))
    }

var Context.cellMode: String
    get() = sharedPrefs.getString("cellMode", "FULL_REPLAY") ?: "FULL_REPLAY"
    set(value) = sharedPrefs.edit {
        putString("cellMode", value)
    }

var Context.environmentSourceMode: String
    get() = sharedPrefs.getString("environmentSourceMode", "AUTO") ?: "AUTO"
    set(value) = sharedPrefs.edit {
        putString("environmentSourceMode", value)
    }

var Context.routingJson: String
    get() = sharedPrefs.getString("routingJson", "").orEmpty()
    set(value) = sharedPrefs.edit {
        putString("routingJson", value)
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

var Context.allowPhysicalRawGnss: Boolean
    get() = sharedPrefs.getBoolean("enableAGPS", FakeLoc.allowPhysicalRawGnss)
    set(value) = sharedPrefs.edit {
        putBoolean("enableAGPS", value)
        FakeLoc.allowPhysicalRawGnss = value
    }

var Context.enableNMEA: Boolean
    get() = sharedPrefs.getBoolean("enableNMEA", true)
    set(value) = sharedPrefs.edit {
        putBoolean("enableNMEA", value)
        FakeLoc.enableNMEA = value
    }

/**
 * Whether the hook should replace Wi-Fi results. The old key was named
 * `disableWifiScan` even though true enabled replacement; keep it as a
 * migration fallback but persist the unambiguous key for new writes.
 */
var Context.mockWifiEnabled: Boolean
    get() = when {
        sharedPrefs.contains("enableMockWifi") -> sharedPrefs.getBoolean("enableMockWifi", FakeLoc.enableMockWifi)
        else -> sharedPrefs.getBoolean("disableWifiScan", FakeLoc.enableMockWifi)
    }
    set(value) = sharedPrefs.edit {
        putBoolean("enableMockWifi", value)
        // Keep older module revisions synchronized during the migration.
        putBoolean("disableWifiScan", value)
        FakeLoc.enableMockWifi = value
    }

var Context.loopBroadcastlocation: Boolean
    get() = sharedPrefs.getBoolean("loopBroadcastLocation", false)
    set(value) = sharedPrefs.edit {
        putBoolean("loopBroadcastLocation", value)
    }
