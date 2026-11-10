package com.system.location.service.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.core.gnss.GnssObservationProfile
import com.system.location.service.core.gnss.GnssCapture
import com.system.location.service.core.gnss.GnssCapturePreview
import com.system.location.service.core.gnss.GnssCapturePreviewer
import com.system.location.service.gnss.GnssCaptureRepository
import com.system.location.service.gnss.GnssReceiverCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val captureMutex = Mutex()
    private var previewer = GnssCapturePreviewer(null)

    init { viewModelScope.launch { reload() } }

    /** A cold flow runs only while the visible screen collects it; eligibility is reevaluated. */
    fun preview(minimumSatellites: Int): Flow<GnssCapturePreview> = flow {
        reload()
        while (currentCoroutineContext().isActive) {
            val source = previewer
            emit(withContext(Dispatchers.Default) { source.current(System.currentTimeMillis(), minimumSatellites) })
            delay(1000)
        }
    }

    private suspend fun accept(capture: GnssCapture?) {
        val profile = withContext(Dispatchers.Default) { capture?.profile() }
        previewer = GnssCapturePreviewer(capture)
        mutableState.value = state.value.copy(profile = profile)
    }

    private suspend fun reload() = captureMutex.withLock {
        try {
            accept(repository.load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            previewer = GnssCapturePreviewer(null)
            mutableState.value = state.value.copy(profile = null, message = "采集记录读取失败：${error.message}")
        } finally { mutableState.value = state.value.copy(loading = false) }
    }

    fun capture() {
        if (state.value.loading || state.value.collecting) return
        mutableState.value = state.value.copy(collecting = true, frames = 0, message = null)
        captureJob = viewModelScope.launch {
            try {
                val capture = collector.capture { frames -> mutableState.value = state.value.copy(frames = frames) }
                captureMutex.withLock {
                    repository.save(capture)
                    accept(capture)
                }
                mutableState.value = state.value.copy(
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
