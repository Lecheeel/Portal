package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C10 · 传感器数值。
 *
 * 期望：加速度/陀螺仪/地磁/气压/计步都有真实量级和变化，不是全 0/-1 常量；
 * 静止时不应持续随机抖动，运动时与轨迹一致。
 */
class SensorCheck : Check {
    override val id = CheckIds.SENSOR
    override val title = "传感器"
    override fun run(context: CheckContext): CheckResult =
        unknown("C10 尚未实现：需要分析传感器采样。")
}

/**
 * C11 · 活动识别 / 计步一致性。
 *
 * 期望：计步在静止时不增长、运动时与速度/步频匹配；Activity Recognition 与运动状态不矛盾。
 */
class ActivityRecognitionCheck : Check {
    override val id = CheckIds.ACTIVITY
    override val title = "活动识别与计步"
    override fun run(context: CheckContext): CheckResult =
        unknown("C11 尚未实现：需要检查计步与活动状态。")
}
