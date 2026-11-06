package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C04 · Provider 列表与属性。
 *
 * 期望：`getProviders` / `getAllProviders` / `getProviderProperties` / `isProviderEnabled`
 * 不暴露 `fused_ext` 或 Portal 的 test provider，且四处结果一致。
 */
class ProviderListCheck : Check {
    override val id = CheckIds.PROVIDER_LIST
    override val title = "Provider 列表与属性"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult =
        unknown("C04 尚未实现：需要检查 Provider 列表、属性与启用状态。")
}
