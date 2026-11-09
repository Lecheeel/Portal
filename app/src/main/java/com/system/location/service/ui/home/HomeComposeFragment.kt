package com.system.location.service.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.core.geo.CoordinateTransform
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.lastKnownLat
import com.system.location.service.ext.lastKnownLng
import com.system.location.service.ext.selectRoute
import com.system.location.service.maps.MapLine
import com.system.location.service.maps.MapServices
import com.system.location.service.maps.PlaceSuggestion
import com.system.location.service.maps.PortalMapView
import com.system.location.service.maps.mapPoint
import com.system.location.service.maps.satelliteMap
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.mock.RockerOverlay
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Compose home screen; the map remains an AndroidView and owns its SDK lifecycle. */
class HomeComposeFragment : Fragment() {
    private val mapModel by activityViewModels<AMapViewModel>()
    private val mockModel by activityViewModels<MockServiceViewModel>()
    private val targetState = kotlinx.coroutines.flow.MutableStateFlow(HomeTarget())
    private var map: PortalMapView? = null
    private var targetMarker: com.system.location.service.maps.MapOverlay? = null
    private var routeLine: MapLine? = null
    private var systemLocation: LocationManager? = null
    private var locationListener: android.location.LocationListener? = null
    private var lastTarget: Pair<Double, Double>? = null
    private var lastAddress: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                HomeScreen(
                    mapModel = mapModel,
                    mockModel = mockModel,
                    targetFlow = targetState,
                    createMap = ::createMap,
                    onMenu = ::openDrawer,
                    onTarget = ::setTarget,
                    onSearch = ::search,
                    onSaveLocation = ::saveLocation,
                    onRocker = ::toggleRocker,
                    onRoutePreview = ::toggleRoutePreview,
                    onMapType = ::toggleMapType,
                )
            }
        }
    }

    private fun createMap(context: Context): View {
        val view = PortalMapView(context)
        map = view
        view.onCreate(null)
        view.onClick { point ->
            val wgs = CoordinateTransform.toWgs84(point)
            setTarget(wgs.latitude to wgs.longitude, null)
        }
        view.onStart()
        view.onResume()
        return view
    }

    private fun openDrawer() {
        val activity = activity as? MainActivity ?: return
        activity.binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun setTarget(wgs: Pair<Double, Double>, name: String?) {
        lastTarget = wgs
        lastAddress = name
        mapModel.markedLoc = wgs
        mapModel.markName = name
        targetState.value = HomeTarget(wgs, name)
        val map = map ?: return
        val gcj = CoordinateTransform.toGcj02(Wgs84(wgs.first, wgs.second))
        targetMarker?.remove()
        targetMarker = map.marker(gcj, name ?: "待确认位置", selected = true)
        map.move(gcj, animate = true)
        viewLifecycleOwner.lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                runCatching { MapServices(requireContext(), map.provider).address(gcj) }.getOrNull()
            }
            if (address != null && lastTarget == wgs) {
                lastAddress = address.address
                mapModel.markName = address.title.ifBlank { address.address }
                targetState.value = HomeTarget(wgs, mapModel.markName)
            }
        }
    }

    private fun search(query: String, result: (List<PlaceSuggestion>) -> Unit) {
        if (query.isBlank()) return
        val map = map ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.IO) {
                runCatching { MapServices(requireContext(), map.provider).suggestions(query) }.getOrNull()
            }
            if (suggestions.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "未找到地点", Toast.LENGTH_SHORT).show()
            } else result(suggestions)
        }
    }

    private fun saveLocation(name: String, address: String, lat: Double, lon: Double) {
        lifecycleScope.launch {
            val result = mockModel.saveLocation(name, address, lat, lon).await()
            Toast.makeText(
                requireContext(),
                if (result.isSuccess) "已加入位置库" else "保存失败：${result.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun toggleRocker() {
        if (RockerOverlay.isEnabled) {
            RockerOverlay.setEnabled(false)
        } else if (!com.system.location.service.android.window.OverlayUtils.hasOverlayPermissions(requireContext())) {
            Toast.makeText(requireContext(), "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
        } else if (!RockerOverlay.setEnabled(true)) {
            Toast.makeText(requireContext(), "悬浮窗显示失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMapType(): Boolean {
        val map = map ?: return false
        val next = !requireContext().satelliteMap
        map.satellite(next)
        requireContext().satelliteMap = next
        return next
    }

    private fun toggleRoutePreview(): Boolean {
        val map = map ?: return false
        val route = requireContext().selectRoute?.route ?: return false
        if (routeLine != null) {
            routeLine?.remove()
            routeLine = null
            return false
        }
        if (route.size >= 2) {
            routeLine = map.line(route.map { it.mapPoint }, Color.argb(200, 59, 130, 246), 12f)
            map.move(route.first().mapPoint, 16f, true)
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        map?.onStart()
        startRealLocation()
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
    }

    override fun onPause() {
        map?.onPause()
        super.onPause()
    }

    override fun onStop() {
        stopRealLocation()
        map?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        map?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        stopRealLocation()
        map?.onDestroy()
        map = null
        targetMarker = null
        routeLine = null
        super.onDestroyView()
    }

    private fun startRealLocation() {
        if (locationListener != null) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        systemLocation = manager
        val listener = android.location.LocationListener { loc ->
            if (loc.isMock || mockModel.runtimeState.value.isActive || loc.latitude == 0.0 && loc.longitude == 0.0) return@LocationListener
            mapModel.currentLocation = loc.latitude to loc.longitude
            map?.currentPosition(CoordinateTransform.toGcj02(com.system.location.service.core.geo.Wgs84(loc.latitude, loc.longitude)))
            requireContext().lastKnownLat = loc.latitude
            requireContext().lastKnownLng = loc.longitude
        }
        locationListener = listener
        runCatching {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.allProviders.contains(provider)) manager.requestLocationUpdates(provider, 1000L, 0f, listener)
            }
        }
    }

    private fun stopRealLocation() {
        val manager = systemLocation ?: return
        locationListener?.let { runCatching { manager.removeUpdates(it) } }
        locationListener = null
        systemLocation = null
    }
}

private data class HomeTarget(
    val point: Pair<Double, Double>? = null,
    val name: String? = null,
)

@Composable
private fun HomeScreen(
    mapModel: AMapViewModel,
    mockModel: MockServiceViewModel,
    targetFlow: kotlinx.coroutines.flow.Flow<HomeTarget>,
    createMap: (Context) -> View,
    onMenu: () -> Unit,
    onTarget: (Pair<Double, Double>, String?) -> Unit,
    onSearch: (String, (List<PlaceSuggestion>) -> Unit) -> Unit,
    onSaveLocation: (String, String, Double, Double) -> Unit,
    onRocker: () -> Unit,
    onRoutePreview: () -> Boolean,
    onMapType: () -> Boolean,
) {
    val context = LocalContext.current
    val runtime by mockModel.runtimeState.collectAsStateWithLifecycle()
    val target by targetFlow.collectAsStateWithLifecycle(initialValue = HomeTarget())
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var saveDialog by remember { mutableStateOf(false) }
    var coordinateDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var saveAddress by remember { mutableStateOf("") }
    var latInput by remember { mutableStateOf("") }
    var lonInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    if (suggestions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { suggestions = emptyList() },
            title = { Text("选择地点") },
            text = {
                LazyColumn(Modifier.height(300.dp)) {
                    items(suggestions) { suggestion ->
                        TextButton(onClick = {
                            suggestions = emptyList()
                            query = suggestion.name
                            val wgs = CoordinateTransform.toWgs84(suggestion.point)
                            onTarget(wgs.latitude to wgs.longitude, suggestion.name)
                        }) { Text("${suggestion.name}\n${suggestion.address}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { suggestions = emptyList() }) { Text("取消") } },
        )
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("保存至位置库") },
            text = {
                Column {
                    OutlinedTextField(saveName, { saveName = it }, label = { Text("名称") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(saveAddress, { saveAddress = it }, label = { Text("地址") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selected = target.point
                    if (selected == null || saveName.isBlank()) {
                        Toast.makeText(context, "请填写名称并选择位置", Toast.LENGTH_SHORT).show()
                    } else {
                        onSaveLocation(saveName, saveAddress.ifBlank { saveName }, selected.first, selected.second)
                        saveDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("取消") } },
        )
    }

    if (coordinateDialog) {
        AlertDialog(
            onDismissRequest = { coordinateDialog = false },
            title = { Text("跳转位置") },
            text = {
                Column {
                    OutlinedTextField(latInput, { latInput = it }, label = { Text("纬度") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(lonInput, { lonInput = it }, label = { Text("经度") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lat = latInput.toDoubleOrNull()
                    val lon = lonInput.toDoubleOrNull()
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        Toast.makeText(context, "坐标格式错误", Toast.LENGTH_SHORT).show()
                    } else {
                        onTarget(lat to lon, null)
                        coordinateDialog = false
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { coordinateDialog = false }) { Text("取消") } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx -> createMap(ctx) }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onMenu) { Text("☰") }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSearch(query) { suggestions = it } }) { Text("搜索") }
                }
            }
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { onMapType() }) { Text("图层") }
                FilledTonalButton(onClick = { if (!onRoutePreview()) Toast.makeText(context, "未选择路线", Toast.LENGTH_SHORT).show() }) { Text("路线") }
                FilledTonalButton(onClick = { coordinateDialog = true }) { Text("坐标") }
                FilledTonalButton(onClick = {
                    mapModel.currentLocation?.let { (lat, lon) ->
                        onTarget(lat to lon, null)
                    } ?: Toast.makeText(context, "正在等待真实定位", Toast.LENGTH_SHORT).show()
                }) { Text("定位") }
            }
            PortalCard {
                Text(
                    target.name ?: "目标位置",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    target.point?.let { (lat, lon) -> String.format(Locale.ROOT, "%.6f, %.6f", lat, lon) }
                        ?: "点击地图选择位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val point = target.point ?: return@Button
                            scope.launch { mockModel.startPoint(point, target.name ?: "目标位置") }
                        },
                        enabled = target.point != null && !runtime.isActive,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (runtime.isActive) "运行中" else "开始模拟") }
                    OutlinedButton(
                        onClick = { scope.launch { mockModel.stopScenario() } },
                        enabled = runtime.isActive,
                        modifier = Modifier.weight(1f),
                    ) { Text("停止模拟") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { saveDialog = true }, enabled = target.point != null, modifier = Modifier.weight(1f)) {
                        Text("保存位置")
                    }
                    OutlinedButton(onClick = onRocker, modifier = Modifier.weight(1f)) {
                        Text(if (RockerOverlay.isEnabled) "关闭悬浮窗" else "悬浮窗")
                    }
                }
            }
        }
    }
}
