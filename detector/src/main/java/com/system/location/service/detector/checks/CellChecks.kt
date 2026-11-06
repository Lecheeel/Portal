package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C09 · 蜂窝/SIM/运营商/信号。
 *
 * 期望：CellInfo、SIM operator、network operator、phone type、signal、service state 自洽；
 * 不能出现 CDMA phone type + GSM/LTE SIM、或 SIM 可用但小区为空。
 */
class CellCheck : Check {
    override val id = CheckIds.CELL
    override val title = "蜂窝/SIM/运营商"
    override val blocking = true
    override fun run(context: CheckContext): CheckResult =
        unknown("C09 尚未实现：需要检查 CellInfo 与 Telephony 字段的一致性。")
}
