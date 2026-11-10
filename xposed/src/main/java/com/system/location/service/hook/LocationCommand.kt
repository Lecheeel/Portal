package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.core.runtime.RuntimeTransferWire
import com.system.location.service.hook.utils.FakeLoc
import kotlin.random.Random
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.runtime.RuntimePositionUpdate

/** P5-08: update/broadcast/sync commands extracted from RemoteCommandHandler. */
object LocationCommand {
    private val positionKeys = setOf("mode", "lat", "lon", "moving_speed", "bearing")

    private fun number(values: Map<String, Any?>, key: String): Double =
        (values[key] as? Double)?.also { require(it.isFinite()) } ?: error("Missing numeric field: $key")

    internal fun prepareMove(values: Map<String, Any?>, state: RuntimeSnapshot): RuntimePositionUpdate {
        require(state.configuration.enabled)
        val distance = number(values, "n").also { require(it >= 0) }
        val bearing = number(values, "bearing")
        val point = FakeLoc.moveLocation(state.latitude, state.longitude, distance, bearing)
        return RuntimePositionUpdate(Wgs84(point.first, point.second),
            if (distance > 0) state.speedMps.toFloat() else 0f, bearing)
    }

    @Suppress("DEPRECATION")
    fun normalizeMove(rely: Bundle, commandId: String): String? {
        if (commandId != "move") return commandId
        val state = FakeLoc.runtimeSnapshots.current()
        if (!state.configuration.enabled) return null
        val update = prepareMove(setOf("n", "bearing").associateWith { rely.get(it) }, state)
        rely.putString("command_id", "update_location")
        rely.putString("mode", "=")
        rely.putDouble("lat", update.coordinate.latitude)
        rely.putDouble("lon", update.coordinate.longitude)
        rely.putFloat("moving_speed", update.movementSpeedMps)
        return "update_location"
    }

    internal fun prepareUpdate(values: Map<String, Any?>, state: RuntimeSnapshot): RuntimePositionUpdate {
        val mode = values["mode"] as? String ?: error("Missing position mode")
        val bearing = if ("bearing" in values) number(values, "bearing") else null
        val speed = if ("moving_speed" in values) values["moving_speed"] as? Float
            ?: error("Invalid movement speed") else 0f
        val point = if (mode == "random") Wgs84(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0)) else {
            val lat = number(values, "lat")
            val lon = number(values, "lon")
            when (mode) {
                "=" -> Wgs84(lat, lon)
                "+" -> Wgs84(state.latitude + lat, state.longitude + lon)
                "-" -> Wgs84(state.latitude - lat, state.longitude - lon)
                "*" -> Wgs84(state.latitude * lat, state.longitude * lon)
                "/" -> {
                    require(lat != 0.0 && lon != 0.0)
                    Wgs84(state.latitude / lat, state.longitude / lon)
                }
                else -> error("Unknown position mode")
            }
        }
        return RuntimePositionUpdate(point, speed, bearing)
    }

    @Suppress("DEPRECATION")
    fun update(rely: Bundle): Boolean {
        val update = prepareUpdate(positionKeys.filter(rely::containsKey).associateWith { rely.get(it) },
            FakeLoc.runtimeSnapshots.current())
        FakeLoc.updateCoordinates(update.coordinate.latitude, update.coordinate.longitude, update.movementSpeedMps,
            bearingDeg = update.bearingDeg)
        if (FakeLoc.enable && FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
        return true
    }

    fun broadcast(): Boolean {
        LocationServiceHook.callOnLocationChanged()
        return true
    }

    /** Diagnostic full snapshot only; live initialization uses atomic subscription instead. */
    fun sync(rely: Bundle): Boolean {
        rely.putByteArray(RuntimeSyncCommand.PAYLOAD, RuntimeTransferWire.encode(FakeLoc.captureTransfer()))
        return true
    }

}
