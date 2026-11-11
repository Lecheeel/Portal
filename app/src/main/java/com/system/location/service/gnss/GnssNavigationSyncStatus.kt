package com.system.location.service.gnss

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class GnssNavigationSyncState(
    val refreshing: Boolean = false,
    val acceptedAtMillis: Long? = null,
    val sourceUrl: String? = null,
    val problem: String? = null,
)

/** App-process diagnostics only; never claims that a remote receiver has consumed a frame. */
internal class GnssNavigationSyncStatus {
    private val mutable = MutableStateFlow(GnssNavigationSyncState())
    val state = mutable.asStateFlow()
    fun begin() { mutable.update { it.copy(refreshing = true, problem = null) } }
    fun accepted(atMillis: Long, sourceUrl: String) {
        mutable.value = GnssNavigationSyncState(acceptedAtMillis = atMillis, sourceUrl = sourceUrl)
    }
    fun failed() { mutable.update { it.copy(refreshing = false,
        problem = "导航数据更新失败，保留已接受资源；无适用数据时继续虚拟天空") } }
    fun cancelled() { mutable.update { it.copy(refreshing = false) } }
    fun synchronizationFailed() { mutable.update { it.copy(
        problem = "导航资源已缓存，但运行配置同步失败；接收端可能仍使用此前资源") } }

    companion object { val shared = GnssNavigationSyncStatus() }
}
