package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C12 · 轨迹统计。
 *
 * 期望：无瞬移（速度突变超限）、时间戳单调、无固定周期抖动（FFT 单频峰）；
 * 需要连续采样 2–5 分钟，样本不足时返回 UNKNOWN。
 */
class TrajectoryCheck : Check {
    override val id = CheckIds.TRAJECTORY
    override val title = "轨迹统计"
    override fun run(context: CheckContext): CheckResult =
        unknown("C12 尚未实现：需要分析轨迹速度/时间戳/周期性。")
}
