package com.system.location.service.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.core.scenario.*
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class PointViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    var name = "手动位置"
    var latitude = application.selectLocation?.lat?.toString() ?: application.lastKnownLat.toString()
    var longitude = application.selectLocation?.lon?.toString() ?: application.lastKnownLng.toString()
    fun saveOrStart(start: Boolean) {
        // Capture the form once; later edits cannot affect an accepted scene.
        val title = name.trim(); val lat = latitude; val lon = longitude
        viewModelScope.launch(Dispatchers.IO) {
            try {
                require(title.isNotBlank()) { "名称不能为空" }
                val point = Wgs84(lat.toDoubleOrNull() ?: error("纬度格式错误"), lon.toDoubleOrNull() ?: error("经度格式错误"))
                val id = UUID.randomUUID().toString()
                if (start) {
                    val context = getApplication<Application>()
                    val scene = Scenario(id, title, point = point, profile = MovementProfile(context.speed, context.altitude,
                        context.accuracy.coerceAtLeast(0.1f), context.reportDuration.toLong()))
                    mutableMessage.value = if (ScenarioRuntime.start(scene).await()) "单点场景已启动" else
                        ScenarioRuntime.state.value.error?.let { "${it.stage}: ${it.reason}\n${it.suggestion}" } ?: "启动失败"
                } else {
                    LibraryRepositories.ensureMigrated()
                    LibraryRepositories.locations.create(SavedLocation(id, title, point))
                    mutableMessage.value = "位置已保存，可在资料库中重命名、复制与导出"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableMessage.value = error.message ?: "位置操作失败" }
        }
    }
}
