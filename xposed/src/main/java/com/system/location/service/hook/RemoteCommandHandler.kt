package com.system.location.service.hook

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Binder
import android.os.SystemClock
import com.system.location.service.hook.security.CommandSecurity
import com.system.location.service.hook.security.PublicationLease
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.hook.scope.HookStatusRegistry
import com.system.location.service.jni.Dobby
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.Logger
import java.util.Collections
import kotlin.random.Random

object RemoteCommandHandler {
    private val proxyBinders by lazy { Collections.synchronizedList(arrayListOf<IBinder>()) }
    private val needProxyCmd = arrayOf("start", "stop", "set_speed_amp", "set_altitude", "set_speed", "update_location", "set_bearing", "move", "put_config", "publish_sample")
    private val publicationLease = PublicationLease()
    private var lastPublishMillis = 0L
    private val security = CommandSecurity()
    private var proxySequence = 0L
    private var lastProxySequence = 0L
    private var isLoadedLibrary = false

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun handleInstruction(command: String, rely: Bundle): Boolean {
        val uid = BinderUtils.getCallerUid()
        val role = BinderUtils.callerRole(uid)
        if (command == "exchange_key") {
            val key = security.exchange(uid, role) ?: return false
            rely.putString("key", key)
            rely.putInt("protocol_version", CommandSecurity.VERSION)
            return true
        }
        if (!security.accept(uid, role, command, rely.getString("command_id"),
                rely.getInt("protocol_version"), rely.getLong("sequence"),
                rely.getLong("sent_at", -1), SystemClock.elapsedRealtime())) return false
        return runCatching { applyInstruction(rely) }.onFailure {
            Logger.error("Invalid remote command", it)
        }.getOrDefault(false)
    }

    @Synchronized
    internal fun resetProxySession() { lastProxySequence = 0 }

    @Synchronized
    internal fun handleProxyInstruction(rely: Bundle): Boolean {
        if (Binder.getCallingUid() != 1000 || FakeLoc.isSystemServerProcess) return false
        val sequence = rely.getLong("proxy_sequence")
        val sent = rely.getLong("sent_at", -1)
        val now = SystemClock.elapsedRealtime()
        if (sequence <= lastProxySequence || sent < 0 || sent > now || now - sent > CommandSecurity.MAX_AGE_MS ||
            rely.getString("command_id") !in needProxyCmd) return false
        lastProxySequence = sequence
        return runCatching { applyInstruction(rely) }.getOrDefault(false)
    }

    private fun applyInstruction(rely: Bundle): Boolean {
        var commandId = rely.getString("command_id") ?: return false
        val incomingSample = if (commandId == "publish_sample") SampleWire.read(rely) ?: return false else null
        if (incomingSample != null && !publicationLease.accepts(incomingSample.elapsedNanos, SystemClock.elapsedRealtimeNanos())) return false
        if (listOf("speed", "altitude", "bearing", "speed_amplitude").any {
                rely.containsKey(it) && !rely.getDouble(it).isFinite()
            }) return false
        if (rely.containsKey("speed") && rely.getDouble("speed") < 0) return false
        if (rely.containsKey("accuracy") && (!rely.getFloat("accuracy").isFinite() || rely.getFloat("accuracy") < 0)) return false
        if (commandId == "move") {
            if (!FakeLoc.enable) return false
            val distance = rely.getDouble("n")
            val bearing = rely.getDouble("bearing")
            if (!distance.isFinite() || distance < 0 || !bearing.isFinite()) return false
            val origin = FakeLoc.coordinatePair()
            val point = FakeLoc.moveLocation(origin.first, origin.second, distance, bearing)
            // Resolve relative movement once, then send the same absolute fix to every process.
            commandId = "update_location"
            rely.putString("command_id", commandId)
            rely.putString("mode", "=")
            rely.putDouble("lat", point.first)
            rely.putDouble("lon", point.second)
            rely.putFloat("moving_speed", if (distance > 0) FakeLoc.speed.toFloat() else 0f)
        }
        if (commandId == "start" || commandId == "update_location" && rely.getString("mode") == "=") {
            if (rely.getDouble("lat", Double.NaN) !in -90.0..90.0 ||
                rely.getDouble("lon", Double.NaN) !in -180.0..180.0) return false
        }

        kotlin.runCatching {
            if (proxyBinders.isNotEmpty() && needProxyCmd.any { it == commandId }) {
                rely.putLong("proxy_sequence", ++proxySequence)
                proxyBinders.removeIf {
                    if (it.isBinderAlive && it.pingBinder()) {
                        val data = Parcel.obtain()
                        try {
                            data.writeBundle(rely)
                            // Forward as system_server, not as the app identity inherited by this Binder call.
                            val identity = Binder.clearCallingIdentity()
                            try { it.transact(1, data, null, 0) }
                            finally { Binder.restoreCallingIdentity(identity) }
                        } finally { data.recycle() }
                        false
                    } else true
                }
            }
        }.onFailure {
            Logger.error("Failed to transact with proxyBinder", it)
        }

        if (FakeLoc.enableDebugLog && commandId !in setOf("publish_sample", "get_runtime_status")) {
            Logger.debug("commandId=$commandId, rely=$rely")
        }

        when (commandId) {
            "publish_sample" -> {
                val fix = incomingSample ?: return false
                if (!publicationLease.renew(fix.elapsedNanos, SystemClock.elapsedRealtimeNanos())) return false
                val needsTicker = !FakeLoc.enable || !FakeLoc.externallyDriven
                FakeLoc.acceptSample(fix)
                FakeLoc.enable = true
                lastPublishMillis = fix.timeMillis
                if (needsTicker) LocationTicker.start()
                if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                return true
            }
            "get_runtime_status" -> {
                rely.putInt("sample_version", SampleWire.VERSION)
                rely.putBoolean("is_start", FakeLoc.enable)
                rely.putLong("last_publish", lastPublishMillis)
                val statuses = HookStatusRegistry.snapshot()
                rely.putInt("hook_installed", statuses.values.count { it.installed })
                rely.putInt("hook_matched", statuses.values.count { it.matched })
                rely.putInt("hook_failed", statuses.values.count { it.failed })
                rely.putInt("hook_skipped", statuses.values.count { it.skipped })
                rely.putStringArrayList("hook_details", ArrayList(statuses.entries.take(128).map { (point, status) ->
                    "$point: supported=${status.supported}, installed=${status.installed}, matched=${status.matched}, skipped=${status.skipped}, failed=${status.failed}; ${status.reason.orEmpty()}"
                }))
                return true
            }
            "set_proxy" -> {
                Logger.info("SubProxyBinder: ${rely.getBinder("proxy")} from ${BinderUtils.getUidPackageNames()}!")
                rely.getBinder("proxy")?.let {
                    proxyBinders.add(it)
                }
                return true
            }
            "start" -> {
                publicationLease.clear()
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)

                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy
                FakeLoc.reportIntervalMs = rely.getLong("report_interval", 100L).coerceIn(50, 1000)
                FakeLoc.updateCoordinates(rely.getDouble("lat"), rely.getDouble("lon"))
                FakeLoc.enable = true
                if (FakeLoc.isSystemServerProcess) {
                    LocationServiceHook.callOnLocationChanged()
                    LocationTicker.start()
                }

                return true
            }
            "stop" -> {
                publicationLease.clear()
                FakeLoc.enable = false
                LocationTicker.stop()
                FakeLoc.hasBearings = false
                if (isLoadedLibrary) {
                    Dobby.setStatus(false)
                }
                return true
            }
            "is_start" -> {
                rely.putBoolean("is_start", FakeLoc.enable)
                return true
            }
            "start_gnss_mock" -> {
                FakeLoc.enableMockGnss = true
                return true
            }
            "stop_gnss_mock" -> {
                FakeLoc.enableMockGnss = false
                return true
            }
            "is_gnss_start" -> {
                rely.putBoolean("is_gnss_start", FakeLoc.enableMockGnss)
                return true
            }
            "is_wifi_mock_start" -> {
                rely.putBoolean("is_wifi_mock_start", FakeLoc.enableMockWifi)
                return true
            }
            "start_wifi_mock" -> {
                FakeLoc.enableMockWifi = true
                return true
            }
            "stop_wifi_mock" -> {
                FakeLoc.enableMockWifi = false
                return true
            }
            "get_location" -> {
                val point = FakeLoc.coordinatePair()
                rely.putDouble("lat", point.first)
                rely.putDouble("lon", point.second)
                return true
            }
            "get_listener_size" -> {
                rely.putInt("size", LocationServiceHook.locationListeners.size)
                return true
            }
            "get_speed" -> {
                rely.putDouble("speed", FakeLoc.speed)
                return true
            }
            "get_bearing" -> {
                rely.putDouble("bearing", FakeLoc.bearing)
                return true
            }
            "get_altitude" -> {
                rely.putDouble("altitude", FakeLoc.altitude)
                return true
            }
            "set_speed_amp" -> {
                val speedAmplitude = rely.getDouble("speed_amplitude", 1.0)
                FakeLoc.speedAmplitude = speedAmplitude
                return true
            }
            "set_altitude" -> {
                val altitude = rely.getDouble("altitude", 0.0)
                FakeLoc.altitude = altitude
                return true
            }
            "set_speed" -> {
                val speed = rely.getDouble("speed", 0.0)
                FakeLoc.speed = speed
                return true
            }
            "set_bearing" -> {
                val bearing = rely.getDouble("bearing", 0.0)
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return true
            }
            "update_location" -> {
                val mode = rely.getString("mode")
                var newLat = rely.getDouble("lat", 0.0)
                var newLon = rely.getDouble("lon", 0.0)
                when(mode) {
                    "+" -> {
                        newLat += FakeLoc.latitude
                        newLon += FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "-" -> {
                        newLat = FakeLoc.latitude - newLat
                        newLon = FakeLoc.longitude - newLon
                        return updateCoordinate(newLat, newLon)
                    }
                    "*" -> {
                        newLat *= FakeLoc.latitude
                        newLon *= FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "/" -> {
                        if (newLat == 0.0 || newLon == 0.0) {
                            return false
                        }
                        newLat /= FakeLoc.latitude
                        newLon /= FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "=" -> {
                        if (rely.containsKey("bearing")) {
                            FakeLoc.bearing = rely.getDouble("bearing")
                            FakeLoc.hasBearings = true
                        }
                        return updateCoordinate(newLat, newLon, rely.getFloat("moving_speed", 0f))
                    }
                    "random" -> {
                        return updateCoordinate(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0))
                    }
                }
                return true
            }
            "put_config" -> {
                val enable = rely.getBoolean("enable", FakeLoc.enable)
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)
                val enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                val disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                val disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                val disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                val needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                var minSatellites = rely.getInt("min_satellites", 12)
                if (minSatellites < 0) {
                    minSatellites = 12
                }

                val enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
                val enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
                val disableRequestGeofence = rely.getBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
                val disableGetFromLocation = rely.getBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)

                FakeLoc.enable = enable
                FakeLoc.enableMockWifi = rely.getBoolean("enable_mock_wifi", FakeLoc.enableMockWifi)
                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy
                FakeLoc.enableDebugLog = enableDebugLog
                FakeLoc.disableGetCurrentLocation = disableGetCurrentLocation
                FakeLoc.disableRegisterLocationListener = disableRegisterLocationListener
                FakeLoc.disableFusedLocation = disableFusedLocation
                FakeLoc.needDowngradeToCdma = needDowngradeToCdma
                FakeLoc.minSatellites = minSatellites
                FakeLoc.enableAGPS = enableAGPS
                FakeLoc.enableNMEA = enableNMEA
                FakeLoc.disableRequestGeofence = disableRequestGeofence
                FakeLoc.disableGetFromLocation = disableGetFromLocation
                FakeLoc.reportIntervalMs = rely.getLong("report_interval", FakeLoc.reportIntervalMs).coerceIn(50, 1000)
                if (FakeLoc.enable) LocationTicker.start()
                return true
            }
            "sync_config" -> {
                if (FakeLoc.enable && FakeLoc.externallyDriven) SampleWire.write(rely, FakeLoc.snapshot())
                rely.putBoolean("enable", FakeLoc.enable)
                val point = FakeLoc.coordinatePair()
                rely.putDouble("latitude", point.first)
                rely.putDouble("longitude", point.second)
                rely.putLong("report_interval", FakeLoc.reportIntervalMs)
                rely.putDouble("altitude", FakeLoc.altitude)
                rely.putDouble("speed", FakeLoc.speed)
                rely.putDouble("speed_amplitude", FakeLoc.speedAmplitude)
                rely.putBoolean("has_bearings", FakeLoc.hasBearings)
                rely.putDouble("bearing", FakeLoc.bearing)
                rely.putParcelable("last_location", FakeLoc.lastLocation)
                rely.putBoolean("enable_log", FakeLoc.enableLog)
                rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
                rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
                rely.putBoolean("hide_mock", FakeLoc.hideMock)
                rely.putBoolean("hook_wifi", FakeLoc.hookWifi)
                rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                return true
            }
            "broadcast_location" -> {
                LocationServiceHook.callOnLocationChanged()
                return true
            }
            "load_library" -> {
                val path = rely.getString("path") ?: return false
                if (!FakeLoc.isSystemServerProcess || path != "/data/local/ext-lib/liblocationext.so") return false

                if (isLoadedLibrary && path.endsWith("liblocationext.so")) {
                    rely.putString("result", "success")
                    return true
                }
                runCatching {
                    System.load(path)
                }.onSuccess {
                    rely.putString("result", "success")
                    isLoadedLibrary = true
                }.onFailure {
                    rely.putString("result", it.stackTraceToString())
                }

                return true
            }
            "native_prepare" -> {
                if (!FakeLoc.isSystemServerProcess || !isLoadedLibrary) return false
                return Dobby.prepareSensors()
            }
            "native_status" -> {
                if (!FakeLoc.isSystemServerProcess) return false
                if (!isLoadedLibrary) return !rely.getBoolean("enabled")
                if (rely.getBoolean("enabled") && (!FakeLoc.enable || !FakeLoc.externallyDriven)) return false
                Dobby.setStatus(rely.getBoolean("enabled"))
                return true
            }
            else -> return false
        }
    }

    @Synchronized internal fun expirePublication(nowNanos: Long): Boolean {
        if (!FakeLoc.externallyDriven || !publicationLease.expired(nowNanos)) return false
        FakeLoc.enable = false
        FakeLoc.hasBearings = false
        publicationLease.clear()
        if (isLoadedLibrary) Dobby.setStatus(false)
        return true
    }

    @Synchronized internal fun restoreSample(rely: Bundle) {
        val fix = SampleWire.read(rely) ?: return
        if (!publicationLease.renew(fix.elapsedNanos, SystemClock.elapsedRealtimeNanos())) {
            FakeLoc.enable = false
            return
        }
        FakeLoc.acceptSample(fix)
        LocationTicker.start()
    }

//    private var hasHookSensor = false
//
//    private fun tryHookSensor(classLoader: ClassLoader = FakeLoc::class.java.classLoader!!) {
//        if (hasHookSensor || proxyBinders.isNullOrEmpty()) return
//
//
//
//        hasHookSensor = true
//    }

//    private fun generateLocation(): Location {
//        val (location, realLocation) = if (FakeLocationConfig.lastLocation != null) {
//            (FakeLocationConfig.lastLocation!! to true)
//        } else {
//            (Location(LocationManager.GPS_PROVIDER) to false)
//        }
//
//        return LocationServiceProxyHook.injectLocation(location, realLocation)
//    }

    private fun updateCoordinate(newLat: Double, newLon: Double, movingSpeed: Float = 0f): Boolean {
        if (newLat in -90.0..90.0 && newLon in -180.0..180.0) {
            FakeLoc.updateCoordinates(newLat, newLon, movingSpeed)
            if (FakeLoc.enable && FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
            return true
        } else {
            Logger.error("Invalid latitude or longitude: $newLat, $newLon")
            return false
        }
    }
}
