package com.system.location.service.hook

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Binder
import android.os.SystemClock
import com.system.location.service.hook.security.CommandSecurity
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.hook.security.SensitiveLog
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.Logger
import kotlin.random.Random

object RemoteCommandHandler {




    private val security = CommandSecurity()




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
    internal fun resetProxySession() = ProxyCommand.resetSession()

    @Synchronized
    internal fun handleProxyInstruction(rely: Bundle): Boolean {
        if (!ProxyCommand.acceptIncoming(rely)) return false
        return runCatching { applyInstruction(rely) }.getOrDefault(false)
    }
    private fun applyInstruction(rely: Bundle): Boolean {
        var commandId = rely.getString("command_id") ?: return false
        val incomingSample = if (commandId == "publish_sample") SampleWire.read(rely) ?: return false else null
        if (incomingSample != null && !SampleCommand.accepts(incomingSample)) return false
        if (listOf("speed", "altitude", "bearing", "speed_amplitude").any {
                rely.containsKey(it) && !rely.getDouble(it).isFinite()
            }) return false
        if (rely.containsKey("speed") && rely.getDouble("speed") < 0) return false
        if (rely.containsKey("accuracy") && (!rely.getFloat("accuracy").isFinite() || rely.getFloat("accuracy") < 0)) return false
        commandId = LocationCommand.normalizeMove(rely, commandId) ?: return false
        if (commandId == "start" || commandId == "update_location" && rely.getString("mode") == "=") {
            if (rely.getDouble("lat", Double.NaN) !in -90.0..90.0 ||
                rely.getDouble("lon", Double.NaN) !in -180.0..180.0) return false
        }

        ProxyCommand.forward(rely, commandId)
        if (FakeLoc.enableDebugLog && commandId !in setOf("publish_sample", "get_runtime_status")) {
            Logger.debug(SensitiveLog.describe(commandId, rely.keySet().associateWith { key ->
                runCatching { rely.get(key)?.toString() }.getOrNull()
            }))
        }

        return when (commandId) {
            "publish_sample" -> SampleCommand.publish(incomingSample ?: return false, rely)
            "get_runtime_status" -> DiagnosticCommand.status(rely, SampleCommand.lastPublishMillis)
            "set_proxy" -> ProxyCommand.add(rely)
            "start" -> ControlCommand.start(rely)
            "stop" -> ControlCommand.stop()
            "is_start" -> ControlCommand.isStart(rely)
            "start_gnss_mock" -> ControlCommand.setGnssMock(true)
            "stop_gnss_mock" -> ControlCommand.setGnssMock(false)
            "is_gnss_start" -> ControlCommand.isGnssMock(rely)
            "is_wifi_mock_start" -> ControlCommand.isWifiMock(rely)
            "start_wifi_mock" -> ControlCommand.setWifiMock(true)
            "stop_wifi_mock" -> ControlCommand.setWifiMock(false)
            "get_location" -> ControlCommand.location(rely)
            "get_listener_size" -> ControlCommand.listenerSize(rely)
            "get_speed" -> ControlCommand.speed(rely)
            "get_bearing" -> ControlCommand.bearing(rely)
            "get_altitude" -> ControlCommand.altitude(rely)
            "set_speed_amp" -> ControlCommand.setSpeedAmplitude(rely)
            "set_altitude" -> ControlCommand.setAltitude(rely)
            "set_speed" -> ControlCommand.setSpeed(rely)
            "set_bearing" -> ControlCommand.setBearing(rely)
            "update_location" -> LocationCommand.update(rely)
            "put_config" -> ConfigCommand.apply(rely)
            "sync_config" -> LocationCommand.sync(rely)
            "broadcast_location" -> LocationCommand.broadcast()
            "load_library" -> NativeCommand.load(rely)
            "native_prepare" -> NativeCommand.prepare()
            "native_status" -> NativeCommand.setEnabled(rely)
            else -> return false
        }
    }

    @Synchronized internal fun expirePublication(nowNanos: Long): Boolean = SampleCommand.expire(nowNanos)
    @Synchronized internal fun restoreSample(rely: Bundle) = SampleCommand.restore(rely)
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
}
