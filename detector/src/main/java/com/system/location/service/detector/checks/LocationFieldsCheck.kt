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
        val optional = suppliedFields(location)
        lines += "已提供可选字段：$optional；未提供的字段不作为异常"

        // The latest callback can be fused/network. It must not hide earlier invalid fields or
        // contradictory GPS extras from the same collection window.
        val samples = context.snapshot.locations.ifEmpty { listOf(location) }.map { sample ->
            LocationFieldSample(sample.provider, sample.time, sample.latitude, sample.longitude,
                if (sample === location) optional else suppliedFields(sample))
        }
        val epochs = nmeaStatusEpochs(context.snapshot).takeIf { it.isNotEmpty() }
        val window = LocationWindowValidation.evaluate(samples, context.snapshot.gnssFrames, epochs)
        issues += window.issues
        lines += "窗口字段检查：${samples.size} 个位置样本；GPS extras=${window.gpsExtrasCount}，同时间窗对账=${window.comparedGpsCount}"
        lines += "GPS 不同坐标=${samples.filter { it.provider == "gps" }.map { it.latitude to it.longitude }.toSet().size}"
        lines += if (epochs != null) "GNSS 配对时间：NMEA 接收机历元（${epochs.size} 组）；位置属于本历元直到下一历元"
            else "GNSS 配对时间：仅有回调到达时间，使用近邻窗口；不能确定精确接收机历元"

        val manager = context.locationManager
        val locationEnabled = runCatching { manager?.isLocationEnabled }.getOrNull()
        val gpsEnabled = runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        lines += "availability: isLocationEnabled=$locationEnabled gpsProviderEnabled=$gpsEnabled " +
            "sampledAvailability=${context.snapshot.availability.size}"
        lines += "可用性是当前状态；缓存位置不因 provider 后来关闭而成为无效样本"

        val discrepancies = window.discrepancies
        lines += discrepancies
        if (issues.isEmpty() && discrepancies.isNotEmpty()) {
            return unknown("同时间窗 GPS extras 与 GnssStatus 存在差异，需核对接收机输出语义和时序", limited(lines))
        }
        return if (issues.isEmpty()) {
            pass("已提供的 Location 字段取值有效；可选字段缺失不构成异常。", limited(lines))
        } else {
            fail("Location 字段非法/矛盾 ${issues.size} 项：${issues.take(3).joinToString("；")}", limited(lines + issues))
        }
    }

    /** Read only supplied optional fields, including API 34 getters which throw when absent. */
    private fun suppliedFields(location: Location): Map<String, Double> = buildMap {
        val extras = location.extras
        for (key in listOf("satellites", "meanCn0", "maxCn0")) {
            if (extras?.containsKey(key) == true) put(key, if (key == "satellites")
                extras.getInt(key).toDouble() else extras.getFloat(key).toDouble())
        }
        if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
        if (location.hasAltitude()) put("altitude", location.altitude)
        if (location.hasVerticalAccuracy()) put("verticalAccuracy", location.verticalAccuracyMeters.toDouble())
        if (location.hasSpeed()) put("speed", location.speed.toDouble())
        if (location.hasSpeedAccuracy()) put("speedAccuracy", location.speedAccuracyMetersPerSecond.toDouble())
        if (location.hasBearing()) put("bearing", location.bearing.toDouble())
        if (location.hasBearingAccuracy()) put("bearingAccuracy", location.bearingAccuracyDegrees.toDouble())
        if (location.hasElapsedRealtimeUncertaintyNanos()) put("elapsedUncertainty", location.elapsedRealtimeUncertaintyNanos)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (location.hasMslAltitude()) put("mslAltitude", location.mslAltitudeMeters)
            if (location.hasMslAltitudeAccuracy()) put("mslAccuracy", location.mslAltitudeAccuracyMeters.toDouble())
        }
    }

    private fun limited(lines: List<String>): List<String> =
        lines.take(MAX_EVIDENCE_LINES).map { it.replace('\n', ' ').take(MAX_EVIDENCE_CHARS) }

    private companion object {
        const val MAX_EVIDENCE_LINES = 20
        const val MAX_EVIDENCE_CHARS = 299
    }
}

internal data class LocationFieldSample(val provider: String?, val timeMillis: Long,
    val latitude: Double, val longitude: Double, val supplied: Map<String, Double>)

internal data class LocationWindowResult(val issues: List<String>, val discrepancies: List<String>,
    val gpsExtrasCount: Int, val comparedGpsCount: Int)

internal object LocationWindowValidation {
    fun evaluate(samples: List<LocationFieldSample>, frames: List<GnssFrameSnapshot>,
        epochs: List<GnssStatusEpoch>? = null): LocationWindowResult {
        val issues = linkedSetOf<String>()
        val discrepancies = linkedSetOf<String>()
        var gpsExtrasCount = 0
        var comparedGpsCount = 0
        samples.forEach { sample ->
            issues += LocationFieldValidation.issues(sample.latitude, sample.longitude, sample.supplied)
                .map { "${sample.provider}@${sample.timeMillis}: $it" }
            if (sample.provider == "gps" && sample.supplied.keys.any { it in GNSS_KEYS }) {
                gpsExtrasCount++
                if (LocationGnssReconciliation.frameFor(sample.timeMillis, frames, epochs) != null) comparedGpsCount++
            }
            discrepancies += LocationGnssReconciliation.compare(sample.provider, sample.timeMillis, sample.supplied, frames, epochs)
        }
        return LocationWindowResult(issues.toList(), discrepancies.toList(), gpsExtrasCount, comparedGpsCount)
    }

    private val GNSS_KEYS = setOf("satellites", "meanCn0", "maxCn0")
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
    fun frameFor(timeMillis: Long, frames: List<GnssFrameSnapshot>, epochs: List<GnssStatusEpoch>?): GnssFrameSnapshot? {
        if (epochs == null) return nearestFrame(timeMillis, frames)
        // Epochs define causal intervals. Selecting by the value of an extras field would hide
        // contradictions; selecting the nearest arrival incorrectly chooses future epochs.
        return epochs.lastOrNull { it.timeMillis <= timeMillis }
            ?.takeIf { timeMillis - it.timeMillis <= 1000 }?.frame
    }

    fun nearestFrame(timeMillis: Long, frames: List<GnssFrameSnapshot>): GnssFrameSnapshot? {
        if (timeMillis <= 0) return null
        return frames.minByOrNull { abs(it.capturedAtMillis - timeMillis) }
            ?.takeIf { abs(it.capturedAtMillis - timeMillis) <= 1000 }
    }

    fun compare(provider: String?, timeMillis: Long, extras: Map<String, Double>,
        frames: List<GnssFrameSnapshot>, epochs: List<GnssStatusEpoch>? = null): List<String> {
        if (provider != "gps" || timeMillis <= 0) return emptyList()
        val frame = frameFor(timeMillis, frames, epochs) ?: return emptyList()
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
