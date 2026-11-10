package com.system.location.service.detector.checks

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult
import com.system.location.service.detector.model.GnssFrameSnapshot
import kotlin.math.abs

/** C05 validates supplied Location fields; optional Android fields are never mandatory. */
@SuppressLint("MissingPermission")
class LocationFieldsCheck : Check {
    override val id = CheckIds.LOCATION_FIELDS
    override val title = "Location 字段与 Availability"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult = try {
        val location = context.snapshot.primaryLocation
            ?: context.snapshot.lastKnownByProvider.values.lastOrNull()
            ?: lastKnownFromManager(context)
        if (location == null) {
            unknown(
                "没有位置样本，无法检查字段完整性。",
                limited(listOf("snapshot.locations=0", "lastKnown=空") + context.snapshot.notes),
            )
        } else {
            evaluate(context, location)
        }
    } catch (error: Exception) {
        unknown("C05 检查异常：${error.javaClass.simpleName}: ${error.message}")
    }

    private fun lastKnownFromManager(context: CheckContext): Location? {
        val manager = context.locationManager ?: return null
        return runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstNotNullOfOrNull { manager.getLastKnownLocation(it) }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun evaluate(context: CheckContext, location: Location): CheckResult {
        val issues = mutableListOf<String>()
        val lines = mutableListOf<String>()
        lines += "provider=${location.provider ?: "?"} time=${location.time} accuracy=${location.accuracy}"

        val extras = location.extras
        val satellites = extras?.getInt("satellites", -1) ?: -1
        val meanCn0 = extras?.getFloat("meanCn0", -1f) ?: -1f
        val maxCn0 = extras?.getFloat("maxCn0", -1f) ?: -1f
        lines += "extras: keys=${extras?.keySet()?.sorted() ?: "null"} satellites=$satellites meanCn0=$meanCn0 maxCn0=$maxCn0"
        val optional = linkedMapOf<String, Double>()
        fun supplied(name: String, present: Boolean, value: () -> Double) {
            if (present) optional[name] = value()
        }
        supplied("satellites", extras?.containsKey("satellites") == true) { satellites.toDouble() }
        supplied("meanCn0", extras?.containsKey("meanCn0") == true) { meanCn0.toDouble() }
        supplied("maxCn0", extras?.containsKey("maxCn0") == true) { maxCn0.toDouble() }
        supplied("accuracy", location.hasAccuracy()) { location.accuracy.toDouble() }
        supplied("altitude", location.hasAltitude()) { location.altitude }
        supplied("verticalAccuracy", location.hasVerticalAccuracy()) { location.verticalAccuracyMeters.toDouble() }
        supplied("speed", location.hasSpeed()) { location.speed.toDouble() }
        supplied("speedAccuracy", location.hasSpeedAccuracy()) { location.speedAccuracyMetersPerSecond.toDouble() }
        supplied("bearing", location.hasBearing()) { location.bearing.toDouble() }
        supplied("bearingAccuracy", location.hasBearingAccuracy()) { location.bearingAccuracyDegrees.toDouble() }
        supplied("elapsedUncertainty", location.hasElapsedRealtimeUncertaintyNanos()) { location.elapsedRealtimeUncertaintyNanos }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34 getters throw when absent. Test presence before evaluating any optional getter.
            supplied("mslAltitude", location.hasMslAltitude()) { location.mslAltitudeMeters }
            supplied("mslAccuracy", location.hasMslAltitudeAccuracy()) { location.mslAltitudeAccuracyMeters.toDouble() }
        }
        issues += LocationFieldValidation.issues(location.latitude, location.longitude, optional)
        lines += "已提供可选字段：$optional；未提供的字段不作为异常"

        val manager = context.locationManager
        val locationEnabled = runCatching { manager?.isLocationEnabled }.getOrNull()
        val gpsEnabled = runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        lines += "availability: isLocationEnabled=$locationEnabled gpsProviderEnabled=$gpsEnabled " +
            "sampledAvailability=${context.snapshot.availability.size}"
        lines += "可用性是当前状态；缓存位置不因 provider 后来关闭而成为无效样本"

        val discrepancies = LocationGnssReconciliation.compare(
            location.provider, location.time, optional, context.snapshot.gnssFrames,
        )
        lines += discrepancies
        if (issues.isEmpty() && discrepancies.isNotEmpty()) {
            return unknown("同时间窗 GPS extras 与 GnssStatus 存在差异，需核对接收机输出语义和时序", limited(lines))
        }
        return if (issues.isEmpty()) {
            pass("已提供的 Location 字段取值有效；可选字段缺失不构成异常。", limited(lines))
        } else {
            fail("Location 字段非法/矛盾 ${issues.size} 项：${issues.joinToString("；")}", limited(lines + issues))
        }
    }

    private fun limited(lines: List<String>): List<String> =
        lines.take(MAX_EVIDENCE_LINES).map { it.replace('\n', ' ').take(MAX_EVIDENCE_CHARS) }

    private companion object {
        const val MAX_EVIDENCE_LINES = 20
        const val MAX_EVIDENCE_CHARS = 299
    }
}

/** Independent numeric validation, deliberately separate from production sanitization. */
internal object LocationFieldValidation {
    fun issues(latitude: Double, longitude: Double, supplied: Map<String, Double>): List<String> {
        val issues = mutableListOf<String>()
        if (!latitude.isFinite() || latitude !in -90.0..90.0) issues += "纬度非法"
        if (!longitude.isFinite() || longitude !in -180.0..180.0) issues += "经度非法"
        supplied.forEach { (name, value) ->
            val valid = value.isFinite() && when (name) {
                "altitude", "mslAltitude" -> true
                "bearing" -> value >= 0 && value < 360
                "meanCn0", "maxCn0" -> value in 0.0..63.0
                "satellites" -> value >= 0 && value % 1.0 == 0.0
                else -> value >= 0
            }
            if (!valid) issues += "$name 字段非法：$value"
        }
        val mean = supplied["meanCn0"]
        val max = supplied["maxCn0"]
        if (mean != null && max != null && mean > max) issues += "meanCn0 超过 maxCn0"
        return issues
    }
}

/** Contemporaneous GPS-only comparison; network and stale status cannot contradict GPS extras. */
internal object LocationGnssReconciliation {
    fun compare(provider: String?, timeMillis: Long, extras: Map<String, Double>,
        frames: List<GnssFrameSnapshot>): List<String> {
        if (provider != "gps" || timeMillis <= 0) return emptyList()
        val frame = frames.minByOrNull { abs(it.capturedAtMillis - timeMillis) }
            ?.takeIf { abs(it.capturedAtMillis - timeMillis) <= 1000 } ?: return emptyList()
        val used = frame.satellites.filter { it.usedInFix }
        val uniqueUsed = used.map { it.constellation to it.svid }.toSet().size
        val differences = mutableListOf<String>()
        extras["satellites"]?.let { count ->
            if (count != uniqueUsed.toDouble()) differences +=
                "GPS extras satellites=$count，同时间窗 GnssStatus 唯一在用=$uniqueUsed（差 ${abs(frame.capturedAtMillis - timeMillis)}ms）"
        }
        // A multiband receiver may choose one band per spacecraft for its extras. Do not average
        // all signal records and then mistake that value for a universal platform contract.
        val minimum = used.minOfOrNull { it.cn0DbHz.toDouble() }
        val maximum = used.maxOfOrNull { it.cn0DbHz.toDouble() }
        for (key in listOf("meanCn0", "maxCn0")) {
            val value = extras[key] ?: continue
            if (minimum != null && maximum != null && value !in minimum..maximum)
                differences += "GPS extras $key=$value 超出同时间窗在用信号范围 $minimum..$maximum"
            if (used.isEmpty() && value > 0)
                differences += "GPS extras $key=$value，但同时间窗 GnssStatus 没有在用信号"
        }
        return differences
    }
}
