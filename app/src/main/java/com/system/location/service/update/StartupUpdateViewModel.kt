package com.system.location.service.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StartupUpdateViewModel : ViewModel() {
    private val pending = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    val pendingUpdate = pending.asStateFlow()
    private val requests = UpdateRequestGate<UpdateChannel>(UpdateChecker.currentChannel())
    private var checkedThisVisit = false
    private var checking = false

    fun check() {
        if (checkedThisVisit || checking) return
        val channel = UpdateChecker.currentChannel()
        requests.select(channel)
        val request = requests.begin() ?: return
        checkedThisVisit = true
        checking = true
        viewModelScope.launch {
            try {
                val result = UpdateChecker.check(channel)
                if (requests.accepts(request, channel) && result is UpdateChecker.Result.UpdateAvailable) pending.value = result.info
            } finally {
                if (requests.accepts(request, channel)) checking = false
            }
        }
    }

    fun channelChanged() {
        val channel = UpdateChecker.currentChannel()
        requests.select(channel)
        pending.value = null
        checkedThisVisit = false
        checking = false
        check()
    }

    fun onBackgrounded() {
        checkedThisVisit = false
    }

    fun onPromptShown() {
        pending.value = null
    }
}
