package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.hook.utils.FakeLoc
import kotlin.random.Random

/** P5-08: update/broadcast/sync commands extracted from RemoteCommandHandler. */
object LocationCommand {
    /** Normalizes relative `move` into an absolute `update_location` before proxy forwarding. */
    fun normalizeMove(rely: Bundle, commandId: String): String? {
        if (commandId != "move") return commandId
        if (!FakeLoc.enable) return null
        val distance = rely.getDouble("n")
        val bearing = rely.getDouble("bearing")
        if (!distance.isFinite() || distance < 0 || !bearing.isFinite()) return null
        val origin = FakeLoc.coordinatePair()
        val point = FakeLoc.moveLocation(origin.first, origin.second, distance, bearing)
        rely.putString("command_id", "update_location")
        rely.putString("mode", "=")
        rely.putDouble("lat", point.first)
        rely.putDouble("lon", point.second)
        rely.putFloat("moving_speed", if (distance > 0) FakeLoc.speed.toFloat() else 0f)
        return "update_location"
    }

    fun update(rely: Bundle): Boolean {
        val mode = rely.getString("mode")
        var newLat = rely.getDouble("lat", 0.0)
        var newLon = rely.getDouble("lon", 0.0)
        when (mode) {
            "+" -> return updateCoordinate(newLat + FakeLoc.latitude, newLon + FakeLoc.longitude)
            "-" -> return updateCoordinate(FakeLoc.latitude - newLat, FakeLoc.longitude - newLon)
            "*" -> return updateCoordinate(newLat * FakeLoc.latitude, newLon * FakeLoc.longitude)
            "/" -> {
                if (newLat == 0.0 || newLon == 0.0) return false
                return updateCoordinate(newLat / FakeLoc.latitude, newLon / FakeLoc.longitude)
            }
            "=" -> {
                if (rely.containsKey("bearing")) {
                    FakeLoc.bearing = rely.getDouble("bearing")
                    FakeLoc.hasBearings = true
                }
                return updateCoordinate(newLat, newLon, rely.getFloat("moving_speed", 0f))
            }
            "random" -> return updateCoordinate(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0))
        }
        return true
    }

    fun broadcast(): Boolean {
        LocationServiceHook.callOnLocationChanged()
        return true
    }

    fun sync(rely: Bundle): Boolean {
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

    private fun updateCoordinate(newLat: Double, newLon: Double, movingSpeed: Float = 0f): Boolean {
        if (newLat in -90.0..90.0 && newLon in -180.0..180.0) {
            FakeLoc.updateCoordinates(newLat, newLon, movingSpeed)
            if (FakeLoc.enable && FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
            return true
        }
        return false
    }
}
