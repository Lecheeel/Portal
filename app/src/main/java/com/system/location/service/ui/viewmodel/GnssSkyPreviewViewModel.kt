package com.system.location.service.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.core.gnss.GnssSimulationMode
import com.system.location.service.core.gnss.ReceiverGnssFrame
import com.system.location.service.core.gnss.RinexNavigationResource
import com.system.location.service.core.gnss.RinexNavigationResourceStore
import com.system.location.service.core.runtime.PreparedRuntimeConfiguration
import com.system.location.service.data.persistence.HookConfigurationPreferences
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.gnss.GnssSkyPreview
import com.system.location.service.gnss.GnssNavigationSyncStatus
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.runtime.ScenarioRuntime
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class GnssSkyPreviewState(
    val frame: ReceiverGnssFrame? = null,
    val message: String = "等待模拟目标坐标",
    val resourceWarning: String? = null,
)

/** Visible-screen prediction only. Does not publish or advance a receiver route. */
class GnssSkyPreviewViewModel(application: Application) : AndroidViewModel(application) {
    internal val navigationSync = GnssNavigationSyncStatus.shared.state
    val state = flow {
        val engine = GnssSkyPreview()
        val file = File(application.noBackupFilesDir, "gnss/navigation-resource.json")
        val store = RinexNavigationResourceStore(file)
        var navigation: RinexNavigationResource? = null
        var resourceWarning: String? = null
        var previousStamp: Pair<Long, Long>? = null
        var ticksSinceRead = 30
        while (currentCoroutineContext().isActive) {
            val next = try {
                val configuration = withContext(Dispatchers.IO) {
                    val fallback = FakeLoc.runtimeSnapshots.current()
                    val stamp = file.lastModified() to file.length()
                    // Atomic replacements usually change the stamp. Also probe periodically for
                    // same-size, same-timestamp replacements; never decode a megabyte each tick.
                    if (stamp != previousStamp || ticksSinceRead >= 30) {
                        try {
                            navigation = store.load()
                            resourceWarning = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            navigation = navigation ?: fallback.configuration.gnssNavigation
                            resourceWarning = "导航缓存读取失败，保留已接受资源；无适用轨道时使用虚拟兜底：${error.message}"
                        }
                        previousStamp = stamp
                        ticksSinceRead = 0
                    }
                    ticksSinceRead++
                    PreparedRuntimeConfiguration.prepare(
                        HookConfigurationPreferences.readGnss(application.sharedPrefs.all, fallback)
                    ).applyTo(fallback).configuration
                }
                val runtime = ScenarioRuntime.state.value
                val sample = runtime.sample?.takeIf { runtime.isActive }
                when {
                    configuration.gnssSimulation.mode == GnssSimulationMode.OFF ->
                        GnssSkyPreviewState(message = "卫星模拟已关闭，保留真实 GNSS 回调")
                    configuration.gnssSimulation.mode == GnssSimulationMode.REPLAY ->
                        GnssSkyPreviewState(message = "当前为实测回放；请查看采集地回放参考")
                    sample == null -> GnssSkyPreviewState(message = "运行场景后，按模拟目标坐标与时间预测天空")
                    else -> GnssSkyPreviewState(
                        frame = withContext(Dispatchers.Default) {
                            engine.frame(sample, configuration.minimumSatellites,
                                configuration.gnssSimulation, navigation, configuration.gnssSource,
                                System.currentTimeMillis())
                        },
                        message = "运行场景目标 · 本地预测，不代表接收端已收到",
                        resourceWarning = resourceWarning,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Drop the previous frame when the target/configuration cannot be evaluated.
                GnssSkyPreviewState(message = "目标天空预测失败：${error.message}", resourceWarning = resourceWarning)
            }
            emit(next)
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
        GnssSkyPreviewState())
}
