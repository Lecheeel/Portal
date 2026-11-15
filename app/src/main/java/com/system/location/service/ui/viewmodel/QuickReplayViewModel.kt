package com.system.location.service.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.gnss.GnssCaptureValidator
import com.system.location.service.core.gnss.GnssSimulationMode
import com.system.location.service.environment.EnvironmentStore
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.gnss.GnssCaptureRepository
import com.system.location.service.gnss.gnssReplayProblem
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QuickReplayState(
    val names: List<String> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

/** Activity-owned so accepting a choice survives closing the map sheet or changing tabs. */
class QuickReplayViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val mutableState = MutableStateFlow(QuickReplayState())
    val state = mutableState.asStateFlow()

    suspend fun refresh() {
        mutableState.value = state.value.copy(loading = true)
        try {
            val names = withContext(Dispatchers.IO) { EnvironmentStore(context).list() }
            mutableState.value = state.value.copy(names = names)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = state.value.copy(message = "环境列表读取失败，请重试") }
        finally { mutableState.value = state.value.copy(loading = false) }
    }

    /** An empty name selects the procedural source. Selecting a profile also enables Wi-Fi. */
    fun environment(name: String) = change {
        val json = if (name.isNotEmpty()) withContext(Dispatchers.IO) {
            EnvironmentProfileCodec.encode(EnvironmentStore(context).load(name))
        } else null
        context.sharedPrefs.edit().apply {
            putBoolean("enableMockWifi", true)
            putBoolean("disableWifiScan", true)
            putString("environmentSourceMode", if (name.isEmpty()) "PROCEDURAL" else "REPLAY")
            putString("replayEnvironmentName", name)
            if (json != null) putString("environmentReplayJson", json)
        }.apply()
    }

    fun disableEnvironment() = change {
        context.sharedPrefs.edit().apply {
            putBoolean("enableMockWifi", false)
            putBoolean("disableWifiScan", false)
            putString("environmentSourceMode", "AUTO")
            putString("replayEnvironmentName", "")
        }.apply()
    }

    fun satellite(mode: GnssSimulationMode?) {
        change {
            if (mode == GnssSimulationMode.REPLAY) {
                val capture = GnssCaptureRepository(context).load() ?: error("尚无卫星采集，请先采集")
                val quality = withContext(Dispatchers.Default) {
                    GnssCaptureValidator.validate(capture, System.currentTimeMillis())
                }
                check(quality.eligible) { "采集已不可用，请重新采集：${quality.issues.joinToString("；")}" }
            }
            context.sharedPrefs.edit().apply {
                putBoolean("gnssReplayOnStart", mode != null)
                if (mode != null) putString("gnssSimulationMode", mode.name)
            }.apply()
        }
    }

    fun replay() {
        change { withContext(Dispatchers.IO) { context.gnssReplayProblem() }?.let { error(it) } }
    }

    private fun change(save: suspend () -> Unit): Job? {
        if (state.value.busy) return null
        mutableState.value = state.value.copy(busy = true, message = null, failed = false)
        return viewModelScope.launch {
            try {
                save()
                val active = ScenarioRuntime.state.value.isActive
                val applied = ScenarioRuntime.applyReplaySettings().await()
                val sourceProblem = if (!applied) withContext(Dispatchers.IO) { context.gnssReplayProblem() } else null
                mutableState.value = state.value.copy(message = when {
                    applied -> "回放设置已应用"
                    sourceProblem != null -> "设置已保存；$sourceProblem"
                    active && ScenarioRuntime.state.value.backend != BackendType.MOCK_PROVIDER ->
                        "设置已保存，应用失败，请检查系统模块连接"
                    else -> "已保存，随 Xposed / Native 定位启动"
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.value = state.value.copy(message = error.message ?: "回放设置失败", failed = true) }
            finally { mutableState.value = state.value.copy(busy = false) }
        }
    }
}
