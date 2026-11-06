package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C15 · 出口 IP / 时区 / locale / carrier 与坐标的一致性。
 *
 * 期望：时区与最后定位经度合理、SIM 国家与坐标不矛盾；可选联网查询出口 IP（默认关闭），
 * 未开启或无法查询时返回 UNKNOWN。
 */
class NetworkConsistencyCheck : Check {
    override val id = CheckIds.NETWORK_CONSISTENCY
    override val title = "网络/时区/carrier 一致性"
    override fun run(context: CheckContext): CheckResult =
        unknown("C15 尚未实现：需要检查时区/locale/carrier 与坐标的一致性。")
}
