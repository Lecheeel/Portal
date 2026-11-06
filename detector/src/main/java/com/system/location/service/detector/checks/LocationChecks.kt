package com.system.location.service.detector.checks

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C01 · 位置模拟标记。
 *
 * 期望：`Location.isMock` / `isFromMockProvider` 以及反射字段（mMock、mIsFromMockProvider 等）
 * 对 Portal 发布的位置都不可见。Android 12–17 的字段名有差异，反射失败只记录，不伪造 PASS。
 */
@SuppressLint("MissingPermission")
class LocationMockCheck : Check {
    override val id = CheckIds.LOCATION_MOCK
    override val title = "位置模拟标记"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult = try {
        val locations = collectLocations(context)
        if (locations.isEmpty()) {
            unknown(
                "没有位置样本，也没有 lastKnownLocation，无法判定 mock 标记。",
                limited(listOf("snapshot.locations=0", "lastKnown=空") + context.snapshot.notes),
            )
        } else {
            evaluate(locations)
        }
    } catch (error: Exception) {
        unknown("C01 检查异常：${error.javaClass.simpleName}: ${error.message}")
    }

    private fun collectLocations(context: CheckContext): List<Location> {
        val locations = context.snapshot.locations.toMutableList()
        if (locations.isEmpty()) {
            val manager = context.locationManager
            if (manager != null) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused").forEach { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { locations += it }
                }
            }
        }
        return locations
    }

    private fun evaluate(locations: List<Location>): CheckResult {
        val sampled = locations.takeLast(MAX_LOCATIONS)
        var mockCount = 0
        val lines = mutableListOf<String>()
        sampled.forEach { location ->
            val reflected = reflectMockFields(location)
            val mock = location.isMock || location.isFromMockProvider || reflected.values.any { it == true }
            if (mock) mockCount++
            lines += "provider=${location.provider ?: "?"} time=${location.time} " +
                "isMock=${location.isMock} isFromMockProvider=${location.isFromMockProvider} " +
                "reflection=$reflected"
        }
        lines += "checked=${sampled.size} mock=$mockCount"
        return if (mockCount > 0) {
            fail(
                "$mockCount/${sampled.size} 个位置仍带 mock 标记；目标 App 可直接读取。",
                limited(lines),
            )
        } else {
            pass(
                "检查 ${sampled.size} 个位置，isMock/isFromMockProvider/反射字段均未发现 mock 标记。",
                limited(lines),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun reflectMockFields(location: Location): Map<String, Boolean?> {
        val result = linkedMapOf<String, Boolean?>()
        FIELD_NAMES.forEach { name ->
            val value = runCatching {
                Location::class.java.getDeclaredField(name).apply { isAccessible = true }.get(location) as? Boolean
            }.getOrNull()
            result[name] = value
        }
        return result
    }

    private fun limited(lines: List<String>): List<String> =
        lines.take(MAX_EVIDENCE_LINES).map { it.replace('\n', ' ').take(MAX_EVIDENCE_CHARS) }

    private companion object {
        const val MAX_LOCATIONS = 50
        const val MAX_EVIDENCE_LINES = 20
        const val MAX_EVIDENCE_CHARS = 299
        val FIELD_NAMES = listOf("mMock", "mIsFromMockProvider", "mAllowed", "mMockLocation")
    }
}
