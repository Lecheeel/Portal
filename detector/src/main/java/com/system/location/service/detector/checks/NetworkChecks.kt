package com.system.location.service.detector.checks

import android.location.Location
import android.telephony.TelephonyManager
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * C15 · 出口 IP / 时区 / locale / carrier 与坐标的一致性。
 *
 * 判定口径：
 * - 无 primaryLocation → UNKNOWN。
 * - 时区偏移与 round(经度/15) 的差（按 24h 归一化，避免日界线误判）|差| >=5 h → FAIL；
 *   1–4 h 只记 evidence，不 FAIL（时区可能是政治性的）。
 * - locale/SIM country/network country 只记录：没有可靠的坐标映射，不据此判 FAIL。
 * - 出口 IP 默认关闭；开启后只请求一次，连接/读取各 3 秒。距离 >500 km → FAIL；
 *   请求失败或明文 HTTP 被系统策略阻止 → UNKNOWN 子项并说明。
 */
class NetworkConsistencyCheck : Check {
    override val id = CheckIds.NETWORK_CONSISTENCY
    override val title = "网络/时区/carrier 一致性"

    override fun run(context: CheckContext): CheckResult = try {
        val location = context.snapshot.primaryLocation
        if (location == null) {
            return unknown(
                "无定位样本，无法比较时区/locale/出口 IP 与坐标的一致性。",
                listOf("primaryLocation=null"),
            )
        }

        val evidence = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val zone = TimeZone.getDefault()
        val offsetHours = zone.getOffset(System.currentTimeMillis()) / 3_600_000.0
        val expectedHours = Math.round(location.longitude / 15.0).toInt()
        val deltaHours = normalizeHours(offsetHours - expectedHours)
        val absDelta = abs(deltaHours)
        addNetworkEvidence(
            evidence,
            "时区：${zone.id} offset=${formatSignedHours(offsetHours)}h，经度 " +
                "${formatNetworkDouble(location.longitude)}° 期望 ${expectedHours}h，" +
                "差 ${formatSignedHours(deltaHours)}h",
        )
        when {
            absDelta >= NETWORK_TIMEZONE_FAIL_HOURS -> failures +=
                "时区偏移与经度期望差 ${formatNetworkDouble(absDelta)}h（>=5h；阈值已放宽，仍可能因政治性时区误判）"
            absDelta >= 1.0 -> addNetworkEvidence(
                evidence,
                "  时区差 ${formatNetworkDouble(absDelta)}h（1–4h，时区可能政治性，仅记录不判 FAIL）",
            )
        }

        val locale = Locale.getDefault()
        addNetworkEvidence(evidence, "locale=${locale.toLanguageTag()}")
        val telephony = context.context.getSystemService(TelephonyManager::class.java)
        val simCountry = runCatching { telephony?.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
        val networkCountry = runCatching { telephony?.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
        addNetworkEvidence(
            evidence,
            "SIM country=${simCountry ?: "不可用"}，network country=${networkCountry ?: "不可用"}" +
                "（无可靠坐标映射，不据此判 FAIL）",
        )

        if (!context.settings.checkNetworkIp) {
            addNetworkEvidence(evidence, "出口 IP：未开启（settings.checkNetworkIp=false），未发起任何网络请求")
            return if (failures.isNotEmpty()) {
                fail("网络/时区一致性异常：${failures.joinToString("；").take(240)}", evidence)
            } else {
                pass("时区与坐标自洽（IP 检查未开启；locale/carrier 仅记录）。", evidence)
            }
        }

        addNetworkEvidence(evidence, "出口 IP：已开启，本轮只查询一次（连接/读取各 3 s 超时，HTTPS）")

        val fetched = fetchIpLocation()
        if (fetched.isFailure) {
            val error = fetched.exceptionOrNull()
            addNetworkEvidence(
                evidence,
                "  查询失败：${error?.javaClass?.simpleName ?: "?"}: " +
                    "${(error?.message ?: "").replace('\n', ' ').take(140)}",
            )
            return if (failures.isNotEmpty()) {
                fail("网络/时区一致性异常：${failures.joinToString("；").take(240)}", evidence)
            } else {
                unknown("IP 检查已开启但查询失败，无法比较出口 IP 与坐标；时区/locale 已检查。", evidence)
            }
        }

        val ip = fetched.getOrNull()
        if (ip == null) {
            return unknown("IP 查询返回空结果，无法比较出口 IP 与坐标。", evidence)
        }
        val distanceKm = networkHaversineMeters(
            ip.latitude,
            ip.longitude,
            location.latitude,
            location.longitude,
        ) / 1000.0
        addNetworkEvidence(
            evidence,
            "  结果：country=${ip.countryCode}，坐标 (${formatNetworkDouble(ip.latitude)}, " +
                "${formatNetworkDouble(ip.longitude)})，与定位距离 ${formatNetworkDouble(distanceKm)} km",
        )
        if (distanceKm > NETWORK_MAX_IP_DISTANCE_KM) {
            failures += "出口 IP 与定位相距 ${formatNetworkDouble(distanceKm)} km（>500 km）"
        }

        return if (failures.isNotEmpty()) {
            fail("网络/时区一致性异常：${failures.joinToString("；").take(240)}", evidence)
        } else {
            pass(
                "时区与坐标自洽；出口 IP country=${ip.countryCode} 与定位相距 " +
                    "${formatNetworkDouble(distanceKm)} km（<=500 km）；locale/carrier 仅记录。",
                evidence,
            )
        }
    } catch (error: Throwable) {
        unknown(
            "C15 检查异常：${error.javaClass.simpleName}: ${error.message}",
            listOf("异常：${error.javaClass.simpleName}"),
        )
    }
}

private data class IpLocation(
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Single HTTPS request to ipinfo.io. The response is parsed in memory and never written to disk or
 * logs; the connection is always disconnected.
 */
private fun fetchIpLocation(): Result<IpLocation> = runCatching {
    val connection = (URL(IP_API_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = IP_API_TIMEOUT_MS
        readTimeout = IP_API_TIMEOUT_MS
        useCaches = false
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = connection.responseCode
        check(code in 200..299) { "HTTP $code" }
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(512)
            var total = 0
            while (total < IP_API_MAX_RESPONSE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, IP_API_MAX_RESPONSE_CHARS - total))
                if (count <= 0) break
                builder.append(buffer, 0, count)
                total += count
            }
            builder.toString()
        }
        val json = JSONObject(body)
        val loc = json.optString("loc")
        val parts = loc.split(',')
        check(parts.size == 2) { "loc 字段缺失或格式异常：$loc" }
        val latitude = parts[0].trim().toDoubleOrNull() ?: Double.NaN
        val longitude = parts[1].trim().toDoubleOrNull() ?: Double.NaN
        check(latitude.isFinite() && longitude.isFinite()) { "lat/lon 无效" }
        IpLocation(
            countryCode = json.optString("country").ifBlank { "?" },
            latitude = latitude,
            longitude = longitude,
        )
    } finally {
        connection.disconnect()
    }
}

private fun networkHaversineMeters(
    latitude1: Double,
    longitude1: Double,
    latitude2: Double,
    longitude2: Double,
): Double {
    val lat1 = Math.toRadians(latitude1)
    val lat2 = Math.toRadians(latitude2)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(longitude2 - longitude1)
    val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Normalizes a UTC-offset difference to [-12, 12] hours so the date line is not a false FAIL. */
private fun normalizeHours(value: Double): Double {
    var hours = value % 24.0
    if (hours > 12.0) hours -= 24.0
    if (hours < -12.0) hours += 24.0
    return hours
}

private fun addNetworkEvidence(evidence: MutableList<String>, line: String) {
    if (evidence.size < NETWORK_MAX_EVIDENCE_LINES) {
        evidence += line.take(NETWORK_MAX_EVIDENCE_CHARS)
    }
}

private fun formatNetworkDouble(value: Double): String =
    if (!value.isFinite()) value.toString() else String.format(Locale.US, "%.2f", value)

private fun formatSignedHours(value: Double): String {
    val text = formatNetworkDouble(value)
    return if (value > 0.0) "+$text" else text
}

private const val IP_API_URL = "https://ipinfo.io/json"
private const val IP_API_TIMEOUT_MS = 3_000
private const val IP_API_MAX_RESPONSE_CHARS = 4_096
private const val NETWORK_TIMEZONE_FAIL_HOURS = 5.0
private const val NETWORK_MAX_IP_DISTANCE_KM = 500.0
private const val NETWORK_MAX_EVIDENCE_LINES = 20
private const val NETWORK_MAX_EVIDENCE_CHARS = 299
