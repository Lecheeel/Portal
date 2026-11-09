package com.system.location.service.ui.mock

import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.R
import com.system.location.service.core.geo.CoordinateTransform
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.planning.DrawingMode
import com.system.location.service.core.planning.Endpoint
import com.system.location.service.core.planning.MapCamera
import com.system.location.service.core.planning.RouteDraft
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
import com.system.location.service.ui.viewmodel.RouteEditViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose route editor. The map and its gesture layer stay as Android Views because they are SDK
 * surfaces; all controls, dialogs and editor chrome are Compose.
 */
class RouteEditComposeFragment : Fragment() {
    private val model by viewModels<RouteEditViewModel>()
    private var mapView: PortalMapView? = null
    private var gestureLayout: RouteGestureLayout? = null
    private var previewLine: MapLine? = null
    private var stroke = mutableListOf<Gcj02>()
    private var cameraRestored = false
    private var renderedKey: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                RouteEditScreen(
                    model = model,
                    createMap = ::createMap,
                    onSearch = ::search,
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
        map.onCreate(null)
        map.onClick { point ->
            if (model.state.value.mode != DrawingMode.FREEHAND) model.pick(point)
        }
        map.onCamera { camera ->
            if (cameraRestored) model.camera(camera)
        }
        mapView = map
        gestureLayout = gesture
        map.onStart()
        map.onResume()
        render(model.state.value)
        return gesture
    }

    private fun render(draft: RouteDraft) {
        val map = mapView ?: return
        val gesture = gestureLayout ?: return
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
            stroke = mutableListOf()
            previewLine = null
        }
        val key = "${draft.start}-${draft.end}-${draft.route?.id}-${draft.strokes.size}-${draft.mode}"
        if (key == renderedKey) return
        renderedKey = key
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
        if (model.state.value.mode != DrawingMode.FREEHAND) return
        val point = map.project(Point(event.x.toInt(), event.y.toInt()))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> stroke = mutableListOf(point)
            MotionEvent.ACTION_MOVE -> if (stroke.lastOrNull() != point) stroke.add(point)
            MotionEvent.ACTION_UP -> {
                if (stroke.lastOrNull() != point) stroke.add(point)
                if (stroke.size >= 2) model.stroke(stroke.toList())
                stroke = mutableListOf()
            }
            MotionEvent.ACTION_CANCEL -> stroke = mutableListOf()
        }
    }

    private fun search(
        query: String,
        onResult: (List<PlaceSuggestion>) -> Unit,
    ) {
        if (query.isBlank()) return
        val map = mapView ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MapServices(requireContext(), map.provider).suggestions(query) }.getOrNull()
            }
            if (result.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "未找到地点，请直接点击地图", Toast.LENGTH_LONG).show()
            } else {
                onResult(result)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        previewLine = null
        mapView?.onPause()
        mapView?.onStop()
        mapView?.onDestroy()
        mapView = null
        gestureLayout?.onStrokeTouch = null
        gestureLayout = null
        super.onDestroyView()
    }
}

@Composable
private fun RouteEditScreen(
    model: RouteEditViewModel,
    createMap: (android.content.Context) -> View,
    onSearch: (String, (List<PlaceSuggestion>) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val draft by model.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var saveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var saveMode by remember { mutableStateOf(RouteMode.ONCE) }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("保存路线") },
            text = {
                Column {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        singleLine = true,
                        label = { Text("路线名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PortalSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                TextButton(onClick = {
                    val route = draft.route?.frozen() ?: return@TextButton
                    if (saveName.isBlank()) {
                        Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
                    } else {
                        model.save(route, saveName, saveMode, true)
                        saveDialog = false
                    }
                }) { Text("保存并启动") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("取消") } },
        )
    }

    if (suggestions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { suggestions = emptyList() },
            title = { Text("选择地点") },
            text = {
                Column {
                    suggestions.forEach { suggestion ->
                        TextButton(onClick = {
                            suggestions = emptyList()
                            model.pick(suggestion.point)
                        }) { Text("${suggestion.name} ${suggestion.address}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { suggestions = emptyList() }) { Text("取消") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DrawingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.mode == mode,
                        onClick = { model.mode(mode) },
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
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索地点") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onSearch(query) { suggestions = it } }) { Text("搜索") }
            }
            if (draft.mode == DrawingMode.PLAN) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = draft.selecting == Endpoint.START,
                        onClick = { model.select(Endpoint.START) },
                        label = { Text("选择起点") },
                    )
                    Spacer(Modifier.padding(4.dp))
                    FilterChip(
                        selected = draft.selecting == Endpoint.END,
                        onClick = { model.select(Endpoint.END) },
                        label = { Text("选择终点") },
                    )
                }
            }
            Text(
                text = when (draft.mode) {
                    DrawingMode.PLAN -> "点击地图设置起终点，沿道路规划步行路线"
                    DrawingMode.POINTS -> "依次点击地图加点，按顺序连接"
                    DrawingMode.FREEHAND -> "单指画线 · 双指移动/缩放"
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
                    draft.message ?: "从地图开始绘制你的路线",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PortalSpacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { model.plan() },
                        enabled = draft.mode == DrawingMode.PLAN && draft.start != null && draft.end != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("规划") }
                    OutlinedButton(
                        onClick = { model.undo() },
                        enabled = draft.mode != DrawingMode.PLAN && draft.strokes.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("撤销") }
                    OutlinedButton(onClick = { model.cancel() }, modifier = Modifier.weight(1f)) {
                        Text("清空")
                    }
                }
                Spacer(Modifier.height(PortalSpacing.sm))
                Button(
                    onClick = {
                        saveName = draft.route?.name ?: "地图路线"
                        saveDialog = true
                    },
                    enabled = draft.route != null,
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
    RouteMode.LOOP -> "循环"
    RouteMode.PING_PONG -> "往返"
}
