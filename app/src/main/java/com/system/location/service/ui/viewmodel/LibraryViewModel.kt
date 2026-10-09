package com.system.location.service.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.core.repository.*
import com.system.location.service.core.scenario.*
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryKind { ROUTES, SCENARIOS, LOCATIONS }
data class LibraryItem(val id: String, val title: String, val detail: String)
data class LibraryUiState(val kind: LibraryKind = LibraryKind.ROUTES, val items: List<LibraryItem> = emptyList(),
    val message: String? = null, val busy: Boolean = false)

/** All persistence, import and scene construction occur off the UI thread. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(LibraryUiState())
    val state = mutable.asStateFlow()
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (action in events) {
                mutable.value = state.value.copy(busy = true)
                try { LibraryRepositories.ensureMigrated(); action(); reload() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutable.value = state.value.copy(message = error.message ?: "资料库操作失败") }
                finally { mutable.value = state.value.copy(busy = false) }
            }
        }
        refresh()
    }
    private fun submit(action: suspend () -> Unit) { events.trySend(action) }
    fun refresh() = submit { }
    fun kind(kind: LibraryKind) = submit { mutable.value = state.value.copy(kind = kind, message = null) }
    private fun reload() {
        val items = when (state.value.kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.list().sortedWith(compareByDescending<SavedRoute> { it.favorite }.thenBy { it.route.name })
                .map { LibraryItem(it.route.id, (if (it.favorite) "★ " else "") + it.route.name, "${it.route.points.size} 个点 · ${modeLabel(it.mode)}") }
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.list().map { LibraryItem(it.id, it.name,
                "${if (it.route != null) "路线" else "单点"} · ${modeLabel(it.mode)} · ${it.profile.speedMps} m/s") }
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.list().map { LibraryItem(it.id, (if (it.favorite) "★ " else "") + it.name,
                "${it.coordinate.latitude}, ${it.coordinate.longitude}\n${it.address}") }
        }
        mutable.value = state.value.copy(items = items)
    }
    private fun modeLabel(mode: RouteMode) = when (mode) {
        RouteMode.ONCE -> "单次"
        RouteMode.LOOP -> "循环"
        RouteMode.PING_PONG -> "往返"
    }
    fun rename(id: String, name: String) = submit {
        require(name.isNotBlank()) { "名称不能为空" }
        when (state.value.kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.rename(id, name)
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.run { update(get(id)!!.copy(name = name)) }
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.run { update(get(id)!!.copy(name = name)) }
        }
    }
    fun copy(id: String, name: String) = submit {
        when (state.value.kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.copy(id, name)
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.copy(id, name)
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.copy(id, name)
        }
    }
    fun delete(id: String) = submit {
        when (state.value.kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.delete(id)
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.delete(id)
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.delete(id)
        }
    }
    fun favorite(id: String) = submit {
        when (state.value.kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.run { favorite(id, !get(id)!!.favorite) }
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.run { get(id)!!.let { update(it.copy(favorite = !it.favorite)) } }
            LibraryKind.SCENARIOS -> error("场景未提供收藏标记")
        }
    }
    fun mode(id: String, mode: RouteMode) = submit {
        when (state.value.kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.mode(id, mode)
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.run { update(get(id)!!.copy(mode = mode)) }
            LibraryKind.LOCATIONS -> error("单点没有路线播放模式")
        }
    }
    fun start(id: String) = submit {
        val context = getApplication<Application>()
        fun profile() = context.movementProfile()
        val scene = when (state.value.kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.get(id)!!.let { Scenario(it.route.id, it.route.name, route = it.route, mode = it.mode, profile = profile()) }
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.get(id)!!
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.get(id)!!.let { Scenario(it.id, it.name, point = it.coordinate, profile = profile()) }
        }.frozen()
        mutable.value = state.value.copy(message = if (ScenarioRuntime.start(scene).await()) "场景已启动"
            else ScenarioRuntime.state.value.error?.let { "${it.stage}: ${it.reason}\n${it.suggestion}" } ?: "启动失败")
    }
    fun import(uri: Uri, kind: LibraryKind) = submit {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val output = StringBuilder(); val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(output.length + count <= VersionedRepository.MAX_DOCUMENT_CHARS) { "导入文件过大" }
                output.append(buffer, 0, count)
            }
            output.toString()
        } ?: error("无法读取文件")
        val count = when (kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.importJson(text).size
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.importJson(text).size
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.importJson(text).size
        }
        mutable.value = state.value.copy(message = "已导入 $count 条记录，重复 ID 已复制为新记录")
    }
    fun export(uri: Uri, kind: LibraryKind) = submit {
        val text = when (kind) {
            LibraryKind.ROUTES -> LibraryRepositories.routes.exportJson()
            LibraryKind.SCENARIOS -> LibraryRepositories.scenarios.exportJson()
            LibraryKind.LOCATIONS -> LibraryRepositories.locations.exportJson()
        }
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
            ?: error("无法写入导出文件")
        mutable.value = state.value.copy(message = "导出完成")
    }
    fun importGpx(uri: Uri) = submit {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val buffer = CharArray(8192); val output = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(output.length + count <= com.system.location.service.core.gpx.GpxCodec.MAX_CHARS) { "GPX 文件过大" }
                output.append(buffer, 0, count)
            }
            output.toString()
        } ?: error("无法读取 GPX")
        val routes = com.system.location.service.core.gpx.GpxCodec.decode(text).map { SavedRoute(it) }
        LibraryRepositories.routes.replaceAll(LibraryRepositories.routes.list() + routes)
        mutable.value = state.value.copy(kind = LibraryKind.ROUTES, message = "已导入 ${routes.size} 条 GPX 路线（WGS84）；不同轨迹段分别保存")
    }
    fun exportGpx(uri: Uri, id: String? = null) = submit {
        val routes = if (id == null) LibraryRepositories.routes.list().map { it.route }
            else listOf(LibraryRepositories.routes.get(id)?.route ?: error("路线不存在"))
        val text = com.system.location.service.core.gpx.GpxCodec.encode(routes)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
            ?: error("无法写入 GPX")
        mutable.value = state.value.copy(message = "GPX 导出完成；仅路线名称与 WGS84 坐标，播放参数请使用 JSON")
    }
    override fun onCleared() { events.close(); super.onCleared() }
}
