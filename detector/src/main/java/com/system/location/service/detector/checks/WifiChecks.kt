package com.system.location.service.detector.checks

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
    override fun run(context: CheckContext): CheckResult =
        unknown("C08 尚未实现：需要检查 WiFi 扫描与连接信息。")
}
