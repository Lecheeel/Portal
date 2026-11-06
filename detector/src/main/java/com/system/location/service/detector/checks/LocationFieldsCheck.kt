package com.system.location.service.detector.checks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C05 · Location 字段、extras、MSL、不确定度与 LocationAvailability。
 *
 * 期望：satellites/meanCn0/maxCn0 extras、MSL 高度与精度、垂直/速度/航向精度、
 * elapsedRealtimeUncertaintyNanos 完整，且与 hasSpeed/hasBearing 自洽。
 * `android.location.LocationAvailability` 不是公开 SDK API，本版用 isLocationEnabled +
 * isProviderEnabled 做可用性一致性判断，P1-05 接入 Hook 后再补逐 provider 的 availability。
 */
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
        if (satellites <= 0) issues += "extras 缺少有效 satellites"
        if (!meanCn0.isFinite() || meanCn0 <= 0f) issues += "extras 缺少有效 meanCn0"
        if (!maxCn0.isFinite() || maxCn0 < meanCn0) issues += "extras 缺少有效 maxCn0"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lines += "msl has=${location.hasMslAltitude()} value=${location.mslAltitudeMeters} " +
                "accHas=${location.hasMslAltitudeAccuracy()} acc=${location.mslAltitudeAccuracyMeters}"
            if (!location.hasMslAltitude() || !location.mslAltitudeMeters.isFinite()) issues += "缺少 MSL 高度"
            if (!location.hasMslAltitudeAccuracy() || location.mslAltitudeAccuracyMeters <= 0f) {
                issues += "缺少 MSL 高度精度"
            }
        } else {
            lines += "msl: API ${Build.VERSION.SDK_INT} < 34，无公开 MSL 字段，跳过"
        }

        lines += "vertical has=${location.hasVerticalAccuracy()} value=${location.verticalAccuracyMeters}"
        if (!location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 0f) issues += "缺少垂直精度"

        lines += "speedAcc has=${location.hasSpeedAccuracy()} value=${location.speedAccuracyMetersPerSecond}"
        if (location.hasSpeed() && (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 0f)) {
            issues += "有速度但缺少速度精度"
        }

        lines += "bearingAcc has=${location.hasBearingAccuracy()} value=${location.bearingAccuracyDegrees}"
        if (location.hasBearing() && (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees < 0f)) {
            issues += "有航向但缺少航向精度"
        }

        lines += "uncertainty has=${location.hasElapsedRealtimeUncertaintyNanos()} value=${location.elapsedRealtimeUncertaintyNanos}"
        if (!location.hasElapsedRealtimeUncertaintyNanos() || location.elapsedRealtimeUncertaintyNanos < 0.0) {
            issues += "缺少 elapsedRealtimeUncertaintyNanos"
        }

        if (location.speed == 0f && location.hasBearing()) {
            issues += "静止时仍输出 bearing=${location.bearing}（应为 hasBearing=false）"
        }

        val manager = context.locationManager
        val locationEnabled = runCatching { manager?.isLocationEnabled }.getOrNull()
        val gpsEnabled = runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        lines += "availability: isLocationEnabled=$locationEnabled gpsProviderEnabled=$gpsEnabled " +
            "sampledAvailability=${context.snapshot.availability.size}"
        if (locationEnabled == false) issues += "已收到位置但系统定位总开关为关闭"
        if (gpsEnabled == false && location.provider == LocationManager.GPS_PROVIDER) {
            issues += "GPS provider 已关闭却仍返回 gps 位置"
        }
        if (context.snapshot.availability.isEmpty()) {
            lines += "note: LocationAvailability 不是公开 SDK API；P1-05 后再逐 provider 校验"
        }

        return if (issues.isEmpty()) {
            pass("Location 字段完整且自洽。", limited(lines))
        } else {
            fail("Location 字段缺失/矛盾 ${issues.size} 项：${issues.joinToString("；")}", limited(lines + issues))
        }
    }

    private fun limited(lines: List<String>): List<String> =
        lines.take(MAX_EVIDENCE_LINES).map { it.replace('\n', ' ').take(MAX_EVIDENCE_CHARS) }

    private companion object {
        const val MAX_EVIDENCE_LINES = 20
        const val MAX_EVIDENCE_CHARS = 299
    }
}
