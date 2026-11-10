package com.system.location.service.gnss

internal data class GnssCaptureSystemState(val instance: String, val generation: Long, val active: Boolean) {
    init { require(instance.isNotBlank() && instance.length <= 256 && generation >= 0) }
}

/** Reject a window if even a complete local/remote simulation session occurred between polls. */
internal class GnssCaptureSessionGuard(
    private val localStartGeneration: Long,
    localActive: Boolean,
    systemRequired: Boolean,
    private val systemState: GnssCaptureSystemState?,
) {
    init {
        require(localStartGeneration >= 0)
        check(!localActive) { "请先停止模拟，再采集真实卫星" }
        check(!systemRequired || systemState != null) { "无法确认系统模拟已停止，请检查后端连接后重新采集" }
        check(systemState?.active != true) { "请先停止系统模拟，再采集真实卫星" }
    }

    fun checkLocal(generation: Long, active: Boolean) {
        check(!active && generation == localStartGeneration) { "采集期间启动过模拟，已废弃本次采集" }
    }

    fun checkSystem(current: GnssCaptureSystemState?) {
        check(current == systemState) { "采集期间系统模拟状态发生变化或无法确认，已废弃本次采集" }
    }
}
