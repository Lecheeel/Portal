package com.system.location.service.core.planning

import com.system.location.service.core.geo.*
import com.system.location.service.core.repository.DocumentStore
import com.system.location.service.core.scenario.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable enum class Endpoint { START, END }
@Serializable enum class DrawingMode { PLAN, POINTS, FREEHAND }
@Serializable enum class DraftPhase { EMPTY, ENDPOINTS, PLANNING, PLANNED, ERROR }
@Serializable data class MapCamera(val target: Gcj02, val zoom: Float, val bearing: Float, val tilt: Float)
@Serializable data class RouteDraft(val schemaVersion: Int = 1, val start: Gcj02? = null, val end: Gcj02? = null,
    val selecting: Endpoint = Endpoint.START, val generation: Long = 0, val phase: DraftPhase = DraftPhase.EMPTY,
    val route: Route? = null, val camera: MapCamera? = null, val message: String? = null,
    val mode: DrawingMode = DrawingMode.PLAN, val strokes: List<List<Gcj02>> = emptyList())
data class PlanRequest(val generation: Long, val start: Gcj02, val end: Gcj02)

object RoutePlanAssembler {
    /** A path contains sequential steps. Alternative paths must be selected before calling this. */
    fun assemble(start: Gcj02, end: Gcj02, segments: List<List<Gcj02>>): Route {
        val points = ArrayList<Gcj02>()
        segments.forEach { segment -> segment.forEach { point ->
            if (points.lastOrNull() != point) points += point
            require(points.size <= 99_998) { "规划结果超过 100000 个路线点，请缩短路线" }
        } }
        require(points.size >= 2 && points.any { it != points.first() }) { "规划返回空路线或没有有效路段" }
        if (points.first() != start) points.add(0, start)
        if (points.last() != end) points += end
        return Route(UUID.randomUUID().toString(), "地图路线", points.map(CoordinateTransform::toWgs84)).frozen()
    }
}

/** Durable data-only editor. Every asynchronous result is checked against its endpoint generation. */
class RouteDraftController(private val store: DocumentStore) {
    private val json = Json { encodeDefaults = true }
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private fun load(): RouteDraft = try {
        store.read()?.let { text ->
            require(text.length <= 20_000_000) { "草稿过大" }
            json.decodeFromString<RouteDraft>(text).also { require(it.schemaVersion == 1) }.let {
                if (it.phase == DraftPhase.PLANNING) it.copy(phase = DraftPhase.ENDPOINTS, route = null, message = "规划已中断，请重新规划")
                else it.copy(route = it.route?.frozen())
            }
        } ?: RouteDraft()
    } catch (error: Exception) { RouteDraft(phase = DraftPhase.ERROR, message = "草稿读取失败：${error.message}") }

    @Synchronized fun select(endpoint: Endpoint) { commit(state.value.copy(selecting = endpoint)) }
    @Synchronized fun pick(point: Gcj02) {
        val old = state.value
        if (old.mode != DrawingMode.PLAN) {
            if (old.mode == DrawingMode.POINTS) appendStroke(listOf(point))
            return
        }
        commit(old.copy(start = if (old.selecting == Endpoint.START) point else old.start,
            end = if (old.selecting == Endpoint.END) point else old.end,
            selecting = Endpoint.END, generation = old.generation + 1,
            route = null, phase = DraftPhase.ENDPOINTS, message = null))
    }
    @Synchronized fun camera(camera: MapCamera) { commit(state.value.copy(camera = camera)) }
    @Synchronized fun cancel() { commit(RouteDraft(generation = state.value.generation + 1, camera = state.value.camera, mode = state.value.mode)) }
    /** Changing modes explicitly starts a new draft; the UI confirms before discarding geometry. */
    @Synchronized fun mode(mode: DrawingMode) {
        if (mode != state.value.mode) commit(RouteDraft(generation = state.value.generation + 1, camera = state.value.camera, mode = mode))
    }
    @Synchronized fun appendStroke(points: List<Gcj02>) {
        if (state.value.mode == DrawingMode.PLAN || points.isEmpty()) return
        val clean = points.fold(ArrayList<Gcj02>()) { result, point ->
            if (result.lastOrNull() != point) result.add(point)
            result
        }.toList()
        require(state.value.strokes.sumOf { it.size } + clean.size <= 10_000) { "手动路线最多 10000 个点，请撤销或缩短路线" }
        rebuildManual(state.value.strokes + listOf(clean))
    }
    @Synchronized fun undo() {
        if (state.value.mode != DrawingMode.PLAN && state.value.strokes.isNotEmpty()) rebuildManual(state.value.strokes.dropLast(1))
    }
    private fun rebuildManual(strokes: List<List<Gcj02>>) {
        val points = strokes.flatten().fold(ArrayList<Gcj02>()) { result, point ->
            if (result.lastOrNull() != point) result.add(point)
            result
        }
        val route = if (points.size >= 2) Route(UUID.randomUUID().toString(), "手动路线",
            points.map(CoordinateTransform::toWgs84)).frozen() else null
        commit(state.value.copy(strokes = strokes, start = points.firstOrNull(), end = points.lastOrNull(),
            route = route, generation = state.value.generation + 1,
            phase = if (route != null) DraftPhase.PLANNED else DraftPhase.EMPTY,
            message = if (route != null) "${points.size} 个点 · 可继续编辑或保存" else "至少添加两个不同的点"))
    }
    @Synchronized fun beginPlan(): PlanRequest? {
        val old = state.value
        if (old.mode != DrawingMode.PLAN) return null
        if (old.start == null || old.end == null || old.start == old.end) {
            commit(old.copy(phase = DraftPhase.ERROR, route = null, message = "请选择不同的起点与终点"))
            return null
        }
        val request = PlanRequest(old.generation + 1, old.start, old.end)
        return if (commit(old.copy(generation = request.generation, route = null, phase = DraftPhase.PLANNING, message = null))) request else null
    }
    @Synchronized fun complete(request: PlanRequest, segments: List<List<Gcj02>>): Boolean {
        if (!current(request)) return false
        return try {
            val route = RoutePlanAssembler.assemble(request.start, request.end, segments)
            commit(state.value.copy(route = route, phase = DraftPhase.PLANNED, message = "路线已规划，请确认后保存或运行"))
        } catch (error: Exception) { fail(request, error.message ?: "路线解析失败"); false }
    }
    @Synchronized fun fail(request: PlanRequest, reason: String) {
        if (current(request)) commit(state.value.copy(route = null, phase = DraftPhase.ERROR, message = reason))
    }
    private fun current(request: PlanRequest) = state.value.generation == request.generation &&
        state.value.start == request.start && state.value.end == request.end && state.value.phase == DraftPhase.PLANNING
    private fun commit(next: RouteDraft): Boolean = try {
        store.writeAtomically(json.encodeToString(next))
        mutable.value = next
        true
    } catch (error: Exception) {
        mutable.value = state.value.copy(phase = DraftPhase.ERROR, message = "草稿保存失败：${error.message}")
        false
    }
}
