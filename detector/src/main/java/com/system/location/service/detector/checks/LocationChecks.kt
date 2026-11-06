package com.system.location.service.detector.checks

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C01 · 位置模拟标记。
 *
 * 期望：`Location.isMock` / `isFromMockProvider` 以及反射字段（mMock、mIsFromMockProvider 等）
 * 对 Portal 发布的位置都不可见；至少覆盖 Android 12–17 的字段差异。
 */
class LocationMockCheck : Check {
    override val id = CheckIds.LOCATION_MOCK
    override val title = "位置模拟标记"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult =
        unknown("C01 尚未实现：需要检查 Location 的 mock 标记与反射字段。")
}
