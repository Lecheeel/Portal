package com.system.location.service.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GnssUiState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val geofence: Boolean = true,
    val fromLocation: Boolean = true,
    val agps: Boolean = true,
    val nmea: Boolean = true,
    val message: String? = null,
)

enum class GnssSetting { GEOFENCE, FROM_LOCATION, AGPS, NMEA }

/** Owns control commands and saved preferences independently of the satellite tab's composition. */
class GnssViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(readPreferences())
    val state = mutableState.asStateFlow()

    private fun readPreferences() = getApplication<Application>().let { context ->
        GnssUiState(geofence = context.enableRequestGeofence, fromLocation = context.enableGetFromLocation,
            agps = context.enableAGPS, nmea = context.enableNMEA)
    }

    suspend fun refresh() {
        if (state.value.busy) return
        val running = ScenarioRuntime.gnssMockRunning()
        // A command may have begun while the query was suspended.
        if (!state.value.busy) mutableState.value = readPreferences().copy(running = running)
    }

    fun toggle() {
        if (state.value.busy) return
        val enabled = !state.value.running
        runCommand {
            val accepted = ScenarioRuntime.setGnssMock(enabled).await()
            mutableState.value = state.value.copy(
                running = ScenarioRuntime.gnssMockRunning(),
                message = if (accepted) null else "GNSS 操作失败，请检查系统模块连接和场景状态",
            )
        }
    }

    fun setPreference(setting: GnssSetting, enabled: Boolean) {
        if (state.value.busy) return
        val context = getApplication<Application>()
        when (setting) {
            GnssSetting.GEOFENCE -> context.enableRequestGeofence = enabled
            GnssSetting.FROM_LOCATION -> context.enableGetFromLocation = enabled
            GnssSetting.AGPS -> context.enableAGPS = enabled
            GnssSetting.NMEA -> context.enableNMEA = enabled
        }
        mutableState.value = readPreferences().copy(running = state.value.running)
        runCommand {
            val applied = ScenarioRuntime.applyConfigNow()
            mutableState.value = state.value.copy(running = ScenarioRuntime.gnssMockRunning(), message = if (applied) "设置已应用"
                else "已保存，下次启动 Xposed/Native 场景生效")
        }
    }

    private fun runCommand(action: suspend () -> Unit) {
        mutableState.value = state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.value = state.value.copy(message = error.message ?: "GNSS 操作失败") }
            finally { mutableState.value = state.value.copy(busy = false) }
        }
    }
}
