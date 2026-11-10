package com.system.location.service.service

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import com.system.location.service.LocationServiceApp
import com.system.location.service.ext.reportDuration
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.ext.loopBroadcastlocation
import com.system.location.service.hook.utils.FakeLoc

object MockServiceHelper {
    const val PROVIDER_NAME = "fused_ext"
    private val commandClient = com.system.location.service.hook.security.CommandClient()


    fun tryInitService(locationManager: LocationManager) {
        runCatching { commandClient.connect(locationManager) }
            .onFailure { Log.e("MockServiceHelper", "Service connection failed", it) }
    }
    fun isMockStart(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_start")
        if(commandClient.send(locationManager, rely)) {
            return rely.getBoolean("is_start")
        }
        return false
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_gnss_start")
        if(commandClient.send(locationManager, rely)) {
            return rely.getBoolean("is_gnss_start")
        }
        return false
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_gnss_mock")
        return commandClient.send(locationManager, rely)
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_gnss_mock")
        return commandClient.send(locationManager, rely)
    }

    fun isWifiMockStart(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_wifi_mock_start")
        if(commandClient.send(locationManager, rely)) {
            return rely.getBoolean("is_wifi_mock_start")
        }
        return false
    }

    fun startWifiMock(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        return commandClient.send(locationManager, rely)
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        return commandClient.send(locationManager, rely)
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
        target: Pair<Double, Double>,
    ): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        rely.putDouble("lat", target.first)
        rely.putDouble("lon", target.second)
        val context = LocationServiceApp.appContext
        rely.putLong("report_interval", broadcastInterval(context))
        return if(commandClient.send(locationManager, rely)) {
            isMockStart(locationManager)
        } else {
            false
        }
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop")
        if (commandClient.send(locationManager, rely)) {
            return !isMockStart(locationManager)
        }
        return false
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        if (!commandClient.connected) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_location")
        if(commandClient.send(locationManager, rely)) {
            return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        if (!commandClient.connected) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_listener_size")
        if(commandClient.send(locationManager, rely)) {
            return rely.getInt("size")
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "broadcast_location")
        return commandClient.send(locationManager, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_bearing")
        rely.putDouble("bearing", bearing)
        return commandClient.send(locationManager, rely)
    }

    fun setSpeed(locationManager: LocationManager, speed: Float): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed")
        rely.putDouble("speed", speed.toDouble())
        return commandClient.send(locationManager, rely)
    }

    fun setAltitude(locationManager: LocationManager, altitude: Double): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_altitude")
        rely.putDouble("altitude", altitude)
        return commandClient.send(locationManager, rely)
    }

    fun setSpeedAmplitude(locationManager: LocationManager, speedAmplitude: Double): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed_amp")
        rely.putDouble("speed_amplitude", speedAmplitude)
        return commandClient.send(locationManager, rely)
    }

    fun getSpeed(locationManager: LocationManager): Float? {
        if (!commandClient.connected) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_speed")
        if(commandClient.send(locationManager, rely)) {
            return rely.getDouble("speed").toFloat()
        }
        return null
    }

    fun getBearing(locationManager: LocationManager): Float? {
        if (!commandClient.connected) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_bearing")
        if(commandClient.send(locationManager, rely)) {
            return rely.getDouble("bearing").toFloat()
        }
        return null
    }

    fun getAltitude(locationManager: LocationManager): Double? {
        if (!commandClient.connected) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_altitude")
        if(commandClient.send(locationManager, rely)) {
            return rely.getDouble("altitude")
        }
        return null
    }

    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "move")
        rely.putDouble("n", distance)
        rely.putDouble("bearing", bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }

        return commandClient.send(locationManager, rely)
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    fun updateLocation(locationManager: LocationManager, lat: Double, lon: Double, mode: String): Boolean {
        if (!commandClient.connected) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "update_location")
        rely.putDouble("lat", lat)
        rely.putDouble("lon", lon)
        rely.putString("mode", mode)
        return commandClient.send(locationManager, rely)
    }

    fun loadLibrary(locationManager: LocationManager, path: String): String? {
        if (!commandClient.connected) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "load_library")
        rely.putString("path", path)
        if(commandClient.send(locationManager, rely)) {
            return rely.getString("result")
        }
        return null
    }

    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        if (!commandClient.connected) {
            return false
        }

        val fallback = FakeLoc.runtimeSnapshots.current()
        val values = try {
            com.system.location.service.data.persistence.HookConfigurationPreferences
                .read(context.sharedPrefs.all, fallback).toMutableMap()
        } catch (error: Exception) {
            com.system.location.service.hook.utils.Logger.error("Configuration preferences invalid: ${error.javaClass.simpleName}")
            return false
        }
        val captureFile = java.io.File(context.noBackupFilesDir, "gnss/receiver-capture.json")
        val capturePayload = runCatching {
            com.system.location.service.core.gnss.GnssCaptureStore(captureFile).load()
                ?.let(com.system.location.service.core.gnss.GnssCaptureWire::encode)
        }.getOrElse {
            com.system.location.service.hook.utils.Logger.error("GNSS capture unavailable: ${it.javaClass.simpleName}")
            return false
        }
        values["gnss_capture"] = capturePayload
        val prepared = try {
            com.system.location.service.core.runtime.PreparedRuntimeConfiguration
                .prepare(values, fallback.configuration.gnssSource)
        } catch (error: Exception) {
            com.system.location.service.hook.utils.Logger.error("Configuration rejected: ${error.javaClass.simpleName}")
            return false
        }
        val rely = Bundle().apply {
            putString("command_id", "put_config")
            values.forEach { (key, value) ->
                when (value) {
                    null -> putString(key, null)
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Double -> putDouble(key, value)
                    else -> error("Unsupported configuration type for $key")
                }
            }
        }
        if (!commandClient.send(locationManager, rely)) return false
        FakeLoc.runtimeSnapshots.update(prepared::applyTo)
        return true
    }

    fun isServiceInit(): Boolean {
        return commandClient.connected
    }

    fun publishSample(
        locationManager: LocationManager,
        sample: com.system.location.service.core.location.LocationSample,
        scenarioId: String? = null,
    ): Boolean {
        val request = Bundle()
        request.putString("command_id", "publish_sample")
        scenarioId?.takeIf { it.isNotBlank() }?.let { request.putString("scenario_id", it) }
        com.system.location.service.hook.security.SampleWire.write(request, sample)
        return commandClient.send(locationManager, request)
    }

    fun runtimeStatus(locationManager: LocationManager): Bundle? {
        val request = Bundle()
        request.putString("command_id", "get_runtime_status")
        return if (commandClient.send(locationManager, request)) request else null
    }

    fun prepareNative(locationManager: LocationManager): Boolean = commandClient.send(locationManager, Bundle().apply {
        putString("command_id", "native_prepare")
    })
    fun setNativeEnabled(locationManager: LocationManager, enabled: Boolean): Boolean = commandClient.send(locationManager, Bundle().apply {
        putString("command_id", "native_status"); putBoolean("enabled", enabled)
    })


    private fun broadcastInterval(context: Context): Long {
        val interval = context.reportDuration.toLong().coerceIn(50, 1000)
        return if (context.loopBroadcastlocation) interval else maxOf(500L, interval)
    }

}
