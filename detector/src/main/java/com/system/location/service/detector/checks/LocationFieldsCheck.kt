package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C05 · Location 字段、extras、MSL、不确定度与 LocationAvailability。
 *
 * 期望：Location 的 satellites/meanCn0/maxCn0 extras、MSL 高度、垂直/速度/航向精度、
 * elapsedRealtimeUncertaintyNanos 完整；Availability 与 provider 状态一致。
 */
class LocationFieldsCheck : Check {
    override val id = CheckIds.LOCATION_FIELDS
    override val title = "Location 字段与 Availability"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult =
        unknown("C05 尚未实现：需要检查 Location 字段、extras 与 LocationAvailability。")
}
