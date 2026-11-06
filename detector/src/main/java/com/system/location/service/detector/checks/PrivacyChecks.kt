package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C02 · AppOps mock_location。
 *
 * 期望：目标包（Portal）的 `OP_MOCK_LOCATION` 不是 MODE_ALLOWED；detector 自身不受影响。
 * 需要 `AppOpsManager.checkOpNoThrow` / `unsafeCheckOpNoThrow`，对跨包查询失败时给出 UNKNOWN。
 */
class AppOpsCheck : Check {
    override val id = CheckIds.APP_OPS
    override val title = "AppOps mock_location"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult =
        unknown("C02 尚未实现：需要检查 AppOps OP_MOCK_LOCATION。")
}

/**
 * C03 · Settings mock_location 与开发者选项。
 *
 * 期望：`Settings.Secure.getString("mock_location")` 与
 * `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` 对普通调用方不可见或为关闭状态。
 */
class SettingsCheck : Check {
    override val id = CheckIds.SETTINGS
    override val title = "Settings mock_location / 开发者选项"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult =
        unknown("C03 尚未实现：需要读取 Settings 的 mock_location 与开发者选项。")
}
