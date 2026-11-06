package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.utils.FakeLoc

/** P5-08: simple control/getter commands extracted from RemoteCommandHandler. */
object ControlCommand {
    fun start(rely: Bundle): Boolean {
        SampleCommand.clearLease()
        FakeLoc.speed = rely.getDouble("speed", FakeLoc.speed)
        FakeLoc.altitude = rely.getDouble("altitude", FakeLoc.altitude)
        FakeLoc.accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)
        FakeLoc.reportIntervalMs = rely.getLong("report_interval", 100L).coerceIn(50, 1000)
        FakeLoc.updateCoordinates(rely.getDouble("lat"), rely.getDouble("lon"))
        FakeLoc.enable = true
        if (FakeLoc.isSystemServerProcess) {
            LocationServiceHook.callOnLocationChanged()
            LocationTicker.start()
        }
        return true
    }

    fun stop(): Boolean {
        SampleCommand.clearLease()
        FakeLoc.enable = false
        LocationTicker.stop()
        FakeLoc.hasBearings = false
        NativeCommand.disable()
        return true
    }

    fun isStart(rely: Bundle): Boolean {
        rely.putBoolean("is_start", FakeLoc.enable)
        return true
    }

    fun setGnssMock(enabled: Boolean): Boolean {
        FakeLoc.enableMockGnss = enabled
        return true
    }

    fun isGnssMock(rely: Bundle): Boolean {
        rely.putBoolean("is_gnss_start", FakeLoc.enableMockGnss)
        return true
    }

    fun setWifiMock(enabled: Boolean): Boolean {
        FakeLoc.enableMockWifi = enabled
        return true
    }

    fun isWifiMock(rely: Bundle): Boolean {
        rely.putBoolean("is_wifi_mock_start", FakeLoc.enableMockWifi)
        return true
    }

    fun location(rely: Bundle): Boolean {
        val point = FakeLoc.coordinatePair()
        rely.putDouble("lat", point.first)
        rely.putDouble("lon", point.second)
        return true
    }

    fun listenerSize(rely: Bundle): Boolean {
        rely.putInt("size", LocationServiceHook.locationListeners.size)
        return true
    }

    fun speed(rely: Bundle): Boolean {
        rely.putDouble("speed", FakeLoc.speed)
        return true
    }

    fun bearing(rely: Bundle): Boolean {
        rely.putDouble("bearing", FakeLoc.bearing)
        return true
    }

    fun altitude(rely: Bundle): Boolean {
        rely.putDouble("altitude", FakeLoc.altitude)
        return true
    }

    fun setSpeedAmplitude(rely: Bundle): Boolean {
        FakeLoc.speedAmplitude = rely.getDouble("speed_amplitude", 1.0)
        return true
    }

    fun setAltitude(rely: Bundle): Boolean {
        FakeLoc.altitude = rely.getDouble("altitude", 0.0)
        return true
    }

    fun setSpeed(rely: Bundle): Boolean {
        FakeLoc.speed = rely.getDouble("speed", 0.0)
        return true
    }

    fun setBearing(rely: Bundle): Boolean {
        FakeLoc.bearing = rely.getDouble("bearing", 0.0)
        FakeLoc.hasBearings = true
        return true
    }
}
