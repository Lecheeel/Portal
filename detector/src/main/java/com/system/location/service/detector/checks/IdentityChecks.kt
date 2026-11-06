package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C13 · PackageManager 中的 Xposed 元数据。
 *
 * 期望：目标包的应用信息里没有 `xposedmodule`/`xposedscope` 元数据，LSPosed 管理器不可判定
 * （或至少不通过 Portal 包暴露模块身份）。P6 迁移 libxposed 后升级为阻断级。
 */
class PackageIdentityCheck : Check {
    override val id = CheckIds.PACKAGE_IDENTITY
    override val title = "PackageManager Xposed 元数据"
    override fun run(context: CheckContext): CheckResult =
        unknown("C13 尚未实现：需要检查目标包的 meta-data。")
}

/**
 * C14 · root / 进程痕迹。
 *
 * 期望：`/proc/self/maps`、`/proc/self/status`（TracerPid）、su 路径中没有明显
 * liblspd/liblocationext/dobby/root 痕迹；研究级，允许 UNKNOWN。
 */
class RootTraceCheck : Check {
    override val id = CheckIds.ROOT_TRACE
    override val title = "root / 进程痕迹"
    override fun run(context: CheckContext): CheckResult =
        unknown("C14 尚未实现：需要检查 /proc 与 root 路径。")
}
