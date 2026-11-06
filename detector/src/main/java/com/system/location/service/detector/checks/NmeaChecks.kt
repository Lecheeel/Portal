package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C07 · NMEA 自洽性。
 *
 * 期望：GGA/RMC/GSA/GSV 校验和正确；GSV 卫星数与 GGA 卫星数、GnssStatus 一致；
 * talker ID 与星座匹配；时间戳与 Location.time 同源。
 */
class NmeaCheck : Check {
    override val id = CheckIds.NMEA
    override val title = "NMEA 语句"
    override fun run(context: CheckContext): CheckResult =
        unknown("C07 尚未实现：需要解析 NMEA 并交叉验证。")
}
