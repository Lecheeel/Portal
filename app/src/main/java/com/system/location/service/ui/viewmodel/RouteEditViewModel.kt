package com.system.location.service.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.amap.AMapRoutePlanner
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.planning.*
import com.system.location.service.core.repository.SavedRoute
import com.system.location.service.core.scenario.*
import com.system.location.service.data.persistence.AtomicDocumentStore
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.data.repository.toHistorical
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Serializes editor events; async planning is generation-checked by the pure draft controller. */
class RouteEditViewModel(application: Application) : AndroidViewModel(application) {
    private val events = Channel<suspend (RouteDraftController) -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(RouteDraft(message = "正在恢复草稿"))
    val state = mutable.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private var planning: Job? = null
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val draft = RouteDraftController(AtomicDocumentStore(File(application.filesDir, "route_draft.json")))
            launch { draft.state.collect { mutable.value = it } }
            for (event in events) {
                try { event(draft) } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableMessage.value = error.message ?: "路线操作失败" }
            }
        }
    }
    fun select(endpoint: Endpoint) { events.trySend { it.select(endpoint) } }
    fun pick(point: Gcj02) { events.trySend { planning?.cancel(); it.pick(point) } }
    fun camera(camera: MapCamera) { events.trySend { it.camera(camera) } }
    fun cancel() { events.trySend { planning?.cancel(); it.cancel() } }
    fun plan() {
        events.trySend { draft ->
            planning?.cancel()
            val request = draft.beginPlan() ?: return@trySend
            planning = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val segments = withTimeout(30_000) { AMapRoutePlanner(getApplication()).plan(request) }
                    draft.complete(request, segments)
                } catch (timeout: TimeoutCancellationException) { draft.fail(request, "路线规划超时，请重试") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { draft.fail(request, error.message ?: "路线规划失败") }
            }
        }
    }
    fun save(snapshot: Route, name: String, mode: RouteMode, start: Boolean) {
        val frozen = snapshot.copy(name = name).frozen()
        events.trySend {
            LibraryRepositories.ensureMigrated()
            val saved = SavedRoute(frozen, mode)
            with(LibraryRepositories.routes) { if (get(frozen.id) == null) create(saved) else update(saved) }
            val context = getApplication<Application>()
            val scene = Scenario(frozen.id, name, route = frozen, mode = mode,
                profile = MovementProfile(context.speed, context.altitude, context.accuracy.coerceAtLeast(0.1f), context.reportDuration.toLong()))
            with(LibraryRepositories.scenarios) { if (get(scene.id) == null) create(scene) else update(scene) }
            context.selectRoute = saved.toHistorical()
            mutableMessage.value = if (start) {
                if (ScenarioRuntime.start(scene).await()) "路线已保存，场景已启动" else ScenarioRuntime.state.value.error?.reason ?: "启动失败"
            } else "路线已保存"
        }
    }
    fun clearMessage() { mutableMessage.value = null }
    override fun onCleared() { events.close(); super.onCleared() }
}
