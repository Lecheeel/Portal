package com.system.location.service.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StartupUpdateViewModel : ViewModel() {
    private val pending = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    val pendingUpdate = pending.asStateFlow()
    private var checkedThisVisit = false
    private var checking = false

    fun check() {
        if (checkedThisVisit || checking) return
        checkedThisVisit = true
        checking = true
        viewModelScope.launch {
            try {
                val result = UpdateChecker.check()
                if (result is UpdateChecker.Result.UpdateAvailable) pending.value = result.info
            } finally {
                checking = false
            }
        }
    }

    fun onBackgrounded() {
        checkedThisVisit = false
    }

    fun onPromptShown() {
        pending.value = null
    }
}
