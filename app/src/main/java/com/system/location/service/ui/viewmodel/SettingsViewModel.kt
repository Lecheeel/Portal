package com.system.location.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RestoreConfigState(val busy: Boolean = false, val message: String? = null)

class SettingsViewModel : ViewModel() {
    private val mutableRestore = MutableStateFlow(RestoreConfigState())
    val restore = mutableRestore.asStateFlow()

    fun restoreLastConfig() {
        if (restore.value.busy) return
        mutableRestore.value = RestoreConfigState(busy = true)
        viewModelScope.launch {
            try {
                val restored = ScenarioRuntime.restoreLastConfig()
                mutableRestore.value = RestoreConfigState(message = if (restored) "已恢复并启动上次场景"
                    else "没有可恢复的配置，或场景正在运行")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableRestore.value = RestoreConfigState(message = error.message ?: "恢复失败，请重试") }
            finally { mutableRestore.value = restore.value.copy(busy = false) }
        }
    }
}
