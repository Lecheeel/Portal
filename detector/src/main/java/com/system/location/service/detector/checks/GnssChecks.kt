package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C06 · GnssStatus 卫星统计。
 *
 * 期望：多星座（不只 BEIDOU）、卫星集合秒级稳定、CN0 随仰角合理、usedInFix 非随机；
 * 每次回调 shuffle 或全部同一星座均判 FAIL。
 */
class GnssStatusCheck : Check {
    override val id = CheckIds.GNSS_STATUS
    override val title = "GnssStatus 卫星"
    override fun run(context: CheckContext): CheckResult =
        unknown("C06 尚未实现：需要分析 GnssStatus 帧。")
}
