package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.RuntimeStart

/** P5-08: simple control/getter commands extracted from RemoteCommandHandler. */
object ControlCommand {
    private val startKeys = setOf("lat", "lon", "speed", "altitude", "accuracy", "report_interval")

    private inline fun <reified T> typed(values: Map<String, Any?>, key: String): T? {
        if (key !in values) return null
        return values[key] as? T ?: throw IllegalArgumentException("Invalid start field: $key")
    }

    internal fun prepareStart(values: Map<String, Any?>): RuntimeStart = RuntimeStart(
        Wgs84(typed<Double>(values, "lat") ?: throw IllegalArgumentException("Missing latitude"),
            typed<Double>(values, "lon") ?: throw IllegalArgumentException("Missing longitude")),
        typed<Double>(values, "speed"), typed<Double>(values, "altitude"), typed<Float>(values, "accuracy"),
        typed<Long>(values, "report_interval") ?: 100L,
    )

    @Suppress("DEPRECATION")
    fun start(rely: Bundle): Boolean {
        val prepared = prepareStart(startKeys.filter(rely::containsKey).associateWith { rely.get(it) })
        FakeLoc.startRuntime(prepared)
        SampleCommand.clearLease()
        if (FakeLoc.isSystemServerProcess) {
            LocationServiceHook.callOnLocationChanged()
            LocationTicker.start()
        }
        return true
    }

    fun stop(): Boolean {
        FakeLoc.stopRuntime()
        SampleCommand.clearLease()
        LocationTicker.stop()
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

    internal fun publishNumber(key: String, raw: Any?): com.system.location.service.core.runtime.RuntimeSnapshot {
        require(raw is Double && raw.isFinite()) { "Invalid numeric control field: $key" }
        if (key == "speed") require(raw >= 0.0)
        if (key == "speed_amplitude") require(raw in 0.0..10.0)
        return FakeLoc.runtimeSnapshots.update { current ->
            val next = when (key) {
                "speed" -> current.copy(speedMps = raw)
                "altitude" -> current.copy(altitudeMeters = raw)
                "bearing" -> current.copy(bearingDeg = raw, hasBearings = true)
                "speed_amplitude" -> current.copy(configuration = current.configuration.copy(speedAmplitude = raw))
                else -> error("Unknown numeric control field")
            }
            next.copy(configRevision = Math.addExact(current.configRevision, 1L))
        }
    }

    @Suppress("DEPRECATION")
    private fun setNumber(rely: Bundle, key: String): Boolean {
        publishNumber(key, rely.get(key))
        return true
    }

    fun setSpeedAmplitude(rely: Bundle) = setNumber(rely, "speed_amplitude")
    fun setAltitude(rely: Bundle) = setNumber(rely, "altitude")
    fun setSpeed(rely: Bundle) = setNumber(rely, "speed")
    fun setBearing(rely: Bundle) = setNumber(rely, "bearing")
}
