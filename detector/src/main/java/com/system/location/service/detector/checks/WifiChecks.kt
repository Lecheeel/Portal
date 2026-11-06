package com.system.location.service.detector.checks

import android.Manifest
import android.content.pm.PackageManager
import android.net.DhcpInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C08 · WiFi 扫描/连接/DHCP。
 *
 * 期望：WiFi 开启时扫描非空、BSSID 合法且不是 02:00:00:00:00:00、连接 AP 出现在扫描结果、
 * DHCP 与连接状态一致。
 */
class WifiCheck : Check {
    override val id = CheckIds.WIFI
    override val title = "WiFi 扫描与连接"
    override val blocking = true

    // connectionInfo/dhcpInfo 在 API 31 已弃用；仍是公开 API，且是 C08 需要的证据来源。
    @Suppress("DEPRECATION")
    override fun run(context: CheckContext): CheckResult {
        val snapshot = context.snapshot
        val evidence = mutableListOf<String>()
        evidence += "wifiEnabled=${snapshot.wifiEnabled}"

        if (!snapshot.wifiEnabled) {
            evidence += "WiFi 关闭：跳过空列表、连接与 DHCP 判定"
            return pass("WiFi 关闭，跳过空列表判定", evidence.clipped())
        }

        var scanResults = snapshot.scanResults
        var scanSource = "snapshot"
        if (scanResults.isEmpty()) {
            val manager = context.context.applicationContext.getSystemService(WifiManager::class.java)
            if (manager == null) {
                evidence += "WifiManager 不可用"
                return unknown("设备未提供 WifiManager，无法读取 WiFi 扫描结果。", evidence.clipped())
            }
            val permission = scanPermission()
            if (context.context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                evidence += "缺少权限：$permission"
                return unknown("WiFi 已开启，但未授予 $permission，无法读取扫描结果。", evidence.clipped())
            }
            val queried = runCatching { manager.scanResults }
            val error = queried.exceptionOrNull()
            if (error != null) {
                evidence += "scanResults 失败：${error.javaClass.simpleName}"
                val reason = if (error is SecurityException) {
                    "读取 WiFi 扫描结果被 SecurityException 拒绝：请检查 $permission。"
                } else {
                    "读取 WiFi 扫描结果失败：${error.javaClass.simpleName}。"
                }
                return unknown(reason, evidence.clipped())
            }
            scanResults = queried.getOrDefault(emptyList())
            scanSource = "实时查询"
        }

        val failures = mutableListOf<String>()
        val invalidBssids = mutableListOf<String>()
        val sentinelBssids = mutableListOf<String>()
        val seenBssids = HashSet<String>()

        scanResults.forEach { scan ->
            val bssid = scan.BSSID?.trim().orEmpty()
            if (!BSSID_PATTERN.matches(bssid)) {
                invalidBssids += bssid.ifEmpty { "<null>" }
            } else if (isSentinelBssid(bssid)) {
                sentinelBssids += bssid
            }
            if (bssid.isNotEmpty()) seenBssids += bssid.uppercase()
        }
        val duplicateBssids = seenBssids.size < scanResults.count { !it.BSSID.isNullOrBlank() }

        if (scanResults.isEmpty()) {
            failures += "WiFi 已开启但扫描结果为空（Portal 当前空列表）"
        }
        if (invalidBssids.isNotEmpty()) {
            failures += "扫描结果含 ${invalidBssids.size} 个非法 BSSID"
        }
        if (sentinelBssids.isNotEmpty()) {
            failures += "扫描结果含 ${sentinelBssids.size} 个哨兵 BSSID（如 02:00:00:00:00:00）"
        }
        if (duplicateBssids) {
            failures += "扫描结果存在重复 BSSID"
        }

        val wifiInfo: WifiInfo? = snapshot.wifiInfo
        val connectedBssid = wifiInfo?.getBSSID()?.trim().orEmpty()
        val connectedSsid = ssidForDisplay(wifiInfo?.getSSID())
        val connected = wifiInfo != null && (
            wifiInfo.getNetworkId() != -1 ||
                (connectedBssid.isNotEmpty() &&
                    BSSID_PATTERN.matches(connectedBssid) &&
                    !isSentinelBssid(connectedBssid))
            )

        if (wifiInfo != null) {
            if (connectedBssid.isNotEmpty() && !BSSID_PATTERN.matches(connectedBssid)) {
                failures += "connectionInfo BSSID 格式非法：${connectedBssid.clip(40)}"
            }
            if (isSentinelBssid(connectedBssid)) {
                failures += "connectionInfo BSSID 是哨兵值 $connectedBssid（Portal 当前值）"
            }
        }

        if (connected) {
            val connectedInScan = scanResults.any { it.BSSID?.trim().equals(connectedBssid, ignoreCase = true) }
            if (connectedBssid.isEmpty()) {
                failures += "已连接但 connectionInfo BSSID 为空"
            } else if (scanResults.isNotEmpty() && !connectedInScan) {
                failures += "已连接 AP 的 BSSID $connectedBssid 不在扫描结果中"
            }
            if (connectedSsid.isEmpty() || connectedSsid == WifiManager.UNKNOWN_SSID) {
                failures += "已连接但 SSID 为 ${connectedSsid.ifEmpty { "<空>" }}"
            }
            val dhcpInfo = snapshot.dhcpInfo
            if (dhcpInfo == null) {
                failures += "已连接但 dhcpInfo 为空"
            } else if (dhcpInfo.ipAddress == 0) {
                failures += "已连接但 dhcpInfo.ipAddress=0"
            }
        }

        evidence += "扫描数=${scanResults.size}（来源：$scanSource）"
        scanResults.take(MAX_SCAN_EVIDENCE).forEachIndexed { index, scan ->
            evidence += "scan[${index + 1}] bssid=${scan.BSSID?.trim().orEmpty()} rssi=${scan.level} " +
                "ssid=${quote(scan.SSID)}"
        }
        evidence += "连接：bssid=${connectedBssid.ifEmpty { "<null>" }} ssid=${quote(connectedSsid)} " +
            "networkId=${wifiInfo?.getNetworkId() ?: "-"} connected=$connected"
        evidence += dhcpEvidence(snapshot.dhcpInfo)
        evidence += "统计：非法=${invalidBssids.size} 哨兵=${sentinelBssids.size} 重复=${if (duplicateBssids) 1 else 0}"
        if (invalidBssids.isNotEmpty()) evidence += "非法 BSSID：${invalidBssids.take(5).joinToString()}"
        if (sentinelBssids.isNotEmpty()) evidence += "哨兵 BSSID：${sentinelBssids.distinct().take(5).joinToString()}"
        if (duplicateBssids) {
            val duplicates = scanResults.groupBy { it.BSSID?.trim().orEmpty().uppercase() }
                .filterValues { it.size > 1 }
                .keys
                .take(5)
            evidence += "重复 BSSID：${duplicates.joinToString()}"
        }

        return if (failures.isEmpty()) {
            pass("WiFi 扫描、连接与 DHCP 自洽", evidence.clipped())
        } else {
            fail(failures.joinToString("；"), evidence.clipped())
        }
    }

    private fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun dhcpEvidence(info: DhcpInfo?): String =
        if (info == null) {
            "DHCP：<null>"
        } else {
            "DHCP：ip=${ipv4(info.ipAddress)} gateway=${ipv4(info.gateway)} dns1=${ipv4(info.dns1)} " +
                "server=${ipv4(info.serverAddress)} lease=${info.leaseDuration}"
        }

    private fun ipv4(value: Int): String =
        "${value and 0xFF}.${(value ushr 8) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 24) and 0xFF}"

    private fun ssidForDisplay(raw: String?): String {
        val ssid = raw?.trim().orEmpty()
        return if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"')) {
            ssid.substring(1, ssid.length - 1)
        } else {
            ssid
        }
    }

    private fun quote(ssid: String): String = "\"${ssid.clip(MAX_SSID_CHARS)}\""

    private fun isSentinelBssid(bssid: String): Boolean =
        bssid.isNotEmpty() && bssid.uppercase() in SENTINEL_BSSIDS

    private fun String.clip(max: Int): String = replace('\n', ' ').take(max)

    private fun List<String>.clipped(): List<String> =
        take(MAX_EVIDENCE_LINES).map { it.clip(MAX_EVIDENCE_CHARS) }

    private companion object {
        const val MAX_SCAN_EVIDENCE = 5
        const val MAX_SSID_CHARS = 32
        const val MAX_EVIDENCE_LINES = 20
        const val MAX_EVIDENCE_CHARS = 280

        val BSSID_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        val SENTINEL_BSSIDS = setOf(
            "02:00:00:00:00:00",
            "00:00:00:00:00:00",
            "FF:FF:FF:FF:FF:FF",
        )
    }
}
