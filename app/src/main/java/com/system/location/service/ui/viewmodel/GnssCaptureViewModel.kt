package com.system.location.service.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.core.gnss.GnssObservationProfile
import com.system.location.service.gnss.GnssCaptureRepository
import com.system.location.service.gnss.GnssReceiverCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GnssCaptureUiState(
    val loading: Boolean = true,
    val collecting: Boolean = false,
    val frames: Int = 0,
    val profile: GnssObservationProfile? = null,
    val message: String? = null,
)

class GnssCaptureViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GnssCaptureRepository(application)
    private val collector = GnssReceiverCollector(application)
    private val mutableState = MutableStateFlow(GnssCaptureUiState())
    val state = mutableState.asStateFlow()
    private var captureJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val capture = repository.load()
                mutableState.value = state.value.copy(profile = capture?.profile())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(message = "采集记录读取失败：${error.message}")
            } finally { mutableState.value = state.value.copy(loading = false) }
        }
    }

    fun capture() {
        if (state.value.loading || state.value.collecting) return
        mutableState.value = state.value.copy(collecting = true, frames = 0, message = null)
        captureJob = viewModelScope.launch {
            try {
                val capture = collector.capture { frames -> mutableState.value = state.value.copy(frames = frames) }
                repository.save(capture)
                mutableState.value = state.value.copy(profile = capture.profile(),
                    message = "已保存真实采集：${capture.frames.size} 帧，原始测量 ${capture.measurements.size} 批，导航电文 ${capture.navigation.size} 条")
            } catch (cancelled: CancellationException) {
                mutableState.value = state.value.copy(message = "采集已取消，保留上次记录")
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(message = error.message ?: "真实卫星采集失败")
            } finally { mutableState.value = state.value.copy(collecting = false) }
        }
    }

    fun cancel() { captureJob?.cancel() }
}
