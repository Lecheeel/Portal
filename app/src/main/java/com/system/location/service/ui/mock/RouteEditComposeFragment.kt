package com.system.location.service.ui.mock

import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.core.geo.CoordinateTransform
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.planning.DrawingMode
import com.system.location.service.core.planning.Endpoint
import com.system.location.service.core.planning.MapCamera
import com.system.location.service.core.planning.RouteDraft
import com.system.location.service.core.scenario.Route
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.lastKnownLat
import com.system.location.service.ext.lastKnownLng
import com.system.location.service.maps.MapLine
import com.system.location.service.maps.MapServices
import com.system.location.service.maps.PlaceSuggestion
import com.system.location.service.maps.PortalMapView
import com.system.location.service.ui.CallbackScheduler
import com.system.location.service.ui.viewmodel.RouteEditViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Compose route editor. The map and its gesture layer stay as Android Views because they are SDK
 * surfaces; all controls, dialogs and editor chrome are Compose.
 */
class RouteEditComposeFragment : Fragment() {
    private val model by viewModels<RouteEditViewModel>()
    private var mapView: PortalMapView? = null
    private var gestureLayout: RouteGestureLayout? = null
    private var previewLine: MapLine? = null
    private var strokeCoordinator: StrokePreviewCoordinator<Gcj02, Gcj02>? = null
    private var lastScreenPoint: Point? = null
    private var mapState: Bundle? = null
    private var mapLifecycleObserver: LifecycleEventObserver? = null
    private var cameraRestored = false
    private var renderedKey: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        cameraRestored = false
        renderedKey = null
        mapState = savedInstanceState
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                RouteEditScreen(
                    model = model,
                    createMap = ::createMap,
                    onSearch = ::search,
                    onPlace = { point ->
                        val map = mapView
                        if (map != null && cameraRestored) {
                            if (model.state.value.mode != DrawingMode.FREEHAND) model.pick(point)
                            map.move(point, 16f, true)
                        }
                    },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch {
                    model.message.collect { message ->
                        if (message != null) {
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                            model.clearMessage()
                        }
                    }
                }
            }
        }
    }

    private fun createMap(context: android.content.Context): View {
        val map = PortalMapView(context)
        val gesture = RouteGestureLayout(context).apply {
            addView(map, ViewGroup.LayoutParams(-1, -1))
            onStrokeTouch = { event -> handleStroke(event, map) }
        }
        map.onCreate(mapState)
        mapState = null
        map.onClick { point ->
            if (mapView === map && cameraRestored && model.state.value.mode != DrawingMode.FREEHAND) model.pick(point)
        }
        map.onCamera { camera ->
            if (mapView === map && cameraRestored) model.camera(camera)
        }
        mapView = map
        gestureLayout = gesture
        strokeCoordinator = StrokePreviewCoordinator(
            frames = object : CallbackScheduler {
                override fun post(delayMillis: Long, callback: Runnable) { gesture.postOnAnimation(callback) }
                override fun remove(callback: Runnable) { gesture.removeCallbacks(callback) }
            },
            convert = { it },
            render = { points ->
                if (mapView === map) {
                    if (previewLine == null) previewLine = map.line(points, Color.rgb(16, 185, 129))
                    else previewLine?.points(points)
                }
            },
            clearPreview = { previewLine?.remove(); previewLine = null },
            commit = { if (mapView === map) model.stroke(it) },
        )
        // Catch up even if AndroidView is first composed after START/RESUME.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> map.onStart()
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> { discardStroke(); map.onPause() }
                Lifecycle.Event.ON_STOP -> map.onStop()
                else -> Unit
            }
        }
        mapLifecycleObserver = observer
        viewLifecycleOwner.lifecycle.addObserver(observer)
        render(model.state.value)
        return gesture
    }

    private fun render(draft: RouteDraft) {
        val map = mapView ?: return
        val gesture = gestureLayout ?: return
        if (RouteEditorActions.from(draft).restoring) {
            gesture.drawingEnabled = false
            return
        }
        if (!cameraRestored) {
            val camera = draft.camera ?: MapCamera(
                CoordinateTransform.toGcj02(Wgs84(requireContext().lastKnownLat, requireContext().lastKnownLng)),
                15f, 0f, 0f,
            )
            map.camera(camera)
            cameraRestored = true
        }
        gesture.drawingEnabled = draft.mode == DrawingMode.FREEHAND
        if (!gesture.drawingEnabled) {
            discardStroke()
        }
        val key = "${draft.start}-${draft.end}-${draft.route?.id}-${draft.strokes.size}-${draft.mode}"
        if (key == renderedKey) return
        renderedKey = key
        discardStroke()
        map.clear()
        previewLine = null
        draft.start?.let { map.marker(it, "起点") }
        draft.end?.let { map.marker(it, "终点") }
        draft.route?.let { route ->
            if (route.points.size >= 2) {
                map.line(route.points.map { CoordinateTransform.toGcj02(it) }, Color.rgb(30, 110, 230))
            }
        }
    }

    private fun handleStroke(event: MotionEvent, map: PortalMapView) {
        if (mapView !== map || !cameraRestored || model.state.value.mode != DrawingMode.FREEHAND) return
        val stroke = strokeCoordinator ?: return
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { discardStroke(); return }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) discardStroke()
        val screen = Point(event.x.toInt(), event.y.toInt())
        val last = lastScreenPoint
        val threshold = 6 * resources.displayMetrics.density
        if (last == null || kotlin.math.hypot((screen.x - last.x).toDouble(), (screen.y - last.y).toDouble()) >= threshold || event.actionMasked == MotionEvent.ACTION_UP) {
            // Project each accepted point once; only the per-frame preview is bounded.
            stroke.add(map.project(screen))
            lastScreenPoint = screen
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) { stroke.finish(); lastScreenPoint = null }
    }

    private fun discardStroke() {
        strokeCoordinator?.cancel()
        lastScreenPoint = null
    }

    private suspend fun search(query: String): List<PlaceSuggestion> {
        val map = mapView ?: return emptyList()
        return MapServices(requireContext().applicationContext, map.provider).suggestions(query)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        mapLifecycleObserver?.let { viewLifecycleOwner.lifecycle.removeObserver(it) }
        mapLifecycleObserver = null
        cameraRestored = false
        gestureLayout?.onStrokeTouch = null
        discardStroke()
        strokeCoordinator = null
        mapView?.onDestroy()
        mapView = null
        gestureLayout = null
        renderedKey = null
        mapState = null
        super.onDestroyView()
    }
}

private data class EditorSearch(val query: String, val generation: Long, val endpoint: Endpoint, val attempt: Long)

@Composable
private fun RouteEditScreen(
    model: RouteEditViewModel,
    createMap: (android.content.Context) -> View,
    onSearch: suspend (String) -> List<PlaceSuggestion>,
    onPlace: (Gcj02) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val draft by model.state.collectAsStateWithLifecycle()
    val actions = RouteEditorActions.from(draft)
    val restoring = actions.restoring
    val planning = actions.planning
    val hasGeometry = actions.hasGeometry
    var query by rememberSaveable { mutableStateOf("") }
    var request by remember { mutableStateOf<EditorSearch?>(null) }
    var searchAttempt by remember { mutableStateOf(0L) }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var clearDialog by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<DrawingMode?>(null) }
    var saveSnapshot by remember { mutableStateOf<Route?>(null) }
    var saveName by remember { mutableStateOf("") }
    var saveMode by remember { mutableStateOf(RouteMode.ONCE) }

    fun cancelSearch() {
        request = null
        suggestions = emptyList()
        searching = false
        searchMessage = null
    }

    LaunchedEffect(draft.generation, draft.selecting, draft.mode) { cancelSearch() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancelSearch()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        try {
            val result = onSearch(current.query)
            coroutineContext.ensureActive()
            if (request != current || model.state.value.generation != current.generation ||
                model.state.value.selecting != current.endpoint || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return@LaunchedEffect
            suggestions = result
            searchMessage = if (result.isEmpty()) "未找到地点，请直接点击地图" else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            if (request == current && model.state.value.generation == current.generation &&
                model.state.value.selecting == current.endpoint &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) searchMessage = error.message ?: "地点搜索失败，请重试"
        } finally {
            // Never update state after effect disposal or cancellation.
            if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && request == current &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) searching = false
        }
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("清空当前路线？") },
            text = { Text("当前未保存的路线将被清空。") },
            confirmButton = { TextButton(onClick = { cancelSearch(); model.cancel(); clearDialog = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("继续编辑") } },
        )
    }
    pendingMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("切换绘制方式？") },
            text = { Text("当前未保存的路线将被清空。") },
            confirmButton = { TextButton(onClick = { cancelSearch(); model.mode(mode); pendingMode = null }) { Text("切换") } },
            dismissButton = { TextButton(onClick = { pendingMode = null }) { Text("继续编辑") } },
        )
    }

    saveSnapshot?.let { snapshot ->
        fun save(start: Boolean) {
            val name = saveName.trim()
            if (name.isNotEmpty()) {
                model.save(snapshot, name, saveMode, start)
                saveSnapshot = null
            }
        }
        AlertDialog(
            onDismissRequest = { saveSnapshot = null },
            title = { Text("确认 ${snapshot.points.size} 个路线点") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("将保存当前完整路线。运行中的场景使用确认时的快照。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        singleLine = true,
                        label = { Text("路线名称") },
                        isError = saveName.isBlank(),
                        supportingText = { if (saveName.isBlank()) Text("名称不能为空") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PortalSpacing.sm))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RouteMode.entries.forEach { mode ->
                            FilterChip(
                                selected = saveMode == mode,
                                onClick = { saveMode = mode },
                                label = { Text(modeLabel(mode)) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { save(false) }, enabled = saveName.isNotBlank()) { Text("保存") }
                    TextButton(onClick = { save(true) }, enabled = saveName.isNotBlank()) { Text("保存并启动") }
                }
            },
            dismissButton = { TextButton(onClick = { saveSnapshot = null }) { Text("取消") } },
        )
    }

    if (suggestions.isNotEmpty() && request?.generation == draft.generation && request?.endpoint == draft.selecting) {
        AlertDialog(
            onDismissRequest = { cancelSearch() },
            title = { Text("选择地点") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    suggestions.forEach { suggestion ->
                        TextButton(onClick = {
                            val current = request
                            val state = model.state.value
                            if (current == null || current.generation != state.generation || current.endpoint != state.selecting) {
                                cancelSearch()
                                return@TextButton
                            }
                            cancelSearch()
                            onPlace(suggestion.point)
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(suggestion.name)
                                if (suggestion.address.isNotBlank()) Text(suggestion.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cancelSearch() }) { Text("取消") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DrawingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.mode == mode,
                        enabled = !restoring,
                        onClick = {
                            if (mode != draft.mode) {
                                if (hasGeometry) pendingMode = mode
                                else { cancelSearch(); model.mode(mode) }
                            }
                        },
                        label = { Text(editorModeLabel(mode)) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; cancelSearch() },
                    enabled = !restoring,
                    singleLine = true,
                    label = { Text("搜索地点") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = query.isNotBlank() && !restoring && !searching,
                    onClick = {
                        suggestions = emptyList()
                        searchMessage = null
                        searching = true
                        request = EditorSearch(query.trim(), draft.generation, draft.selecting, ++searchAttempt)
                    },
                ) { Text(if (searching) "搜索中" else "搜索") }
            }
            if (searching || planning || restoring) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            searchMessage?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (draft.mode == DrawingMode.PLAN) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = draft.selecting == Endpoint.START,
                        enabled = !restoring,
                        onClick = { cancelSearch(); model.select(Endpoint.START) },
                        label = { Text(if (draft.start == null) "选择起点" else "起点已选") },
                    )
                    Spacer(Modifier.padding(4.dp))
                    FilterChip(
                        selected = draft.selecting == Endpoint.END,
                        enabled = !restoring,
                        onClick = { cancelSearch(); model.select(Endpoint.END) },
                        label = { Text(if (draft.end == null) "选择终点" else "终点已选") },
                    )
                }
            }
            Text(
                text = when (draft.mode) {
                    DrawingMode.PLAN -> "点击地图设置起终点，沿道路规划步行路线"
                    DrawingMode.POINTS -> "依次点击地图加点，按顺序连接"
                    DrawingMode.FREEHAND -> "单指画线 · 双指移动/缩放并取消当前笔画 · 多笔之间直线连接"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx -> createMap(ctx) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            PortalCard(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (planning) "正在规划步行路线…" else draft.message ?: if (draft.mode == DrawingMode.PLAN) {
                        "起点${if (draft.start == null) "未选" else "已选"} · 终点${if (draft.end == null) "未选" else "已选"}"
                    } else "从地图开始绘制你的路线",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PortalSpacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.mode == DrawingMode.PLAN) {
                        OutlinedButton(
                            onClick = { cancelSearch(); model.plan() },
                            enabled = actions.canPlan,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (planning) "规划中…" else "规划") }
                    } else {
                        OutlinedButton(
                            onClick = { cancelSearch(); model.undo() },
                            enabled = actions.canUndo,
                            modifier = Modifier.weight(1f),
                        ) { Text("撤销") }
                    }
                    OutlinedButton(
                        onClick = { clearDialog = true },
                        enabled = !restoring && hasGeometry,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("清空")
                    }
                }
                Spacer(Modifier.height(PortalSpacing.sm))
                Button(
                    onClick = {
                        val current = model.state.value
                        if (RouteEditorActions.from(current).canSave) {
                            saveSnapshot = current.route?.frozen()
                            saveName = saveSnapshot?.name ?: "地图路线"
                            saveMode = RouteMode.ONCE
                        }
                    },
                    enabled = actions.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存路线") }
            }
        }
    }
}

private fun editorModeLabel(mode: DrawingMode) = when (mode) {
    DrawingMode.PLAN -> "规划"
    DrawingMode.POINTS -> "加点"
    DrawingMode.FREEHAND -> "画线"
}

private fun modeLabel(mode: RouteMode) = when (mode) {
    RouteMode.ONCE -> "单次"
    RouteMode.LOOP -> "循环（闭合回起点）"
    RouteMode.PING_PONG -> "往返"
}
