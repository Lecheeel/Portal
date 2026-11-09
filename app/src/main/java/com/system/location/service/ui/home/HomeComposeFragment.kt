package com.system.location.service.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.system.location.service.maps.MapProvider
import com.system.location.service.ext.wgs84
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.size
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
    private var cameraPolicy = HomeCameraPolicy()
    private val locationState = kotlinx.coroutines.flow.MutableStateFlow(HomeLocationState())
    private var amapLocation: AMapLocationClient? = null
    private var locationTimeout: Job? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) locate() else showLocationIssue(HomeLocationIssue.PERMISSION)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mockModel.runtimeState.collect { runtime ->
                    if (runtime.isActive) {
                        stopRealLocation()
                        if (locationState.value.loading) showLocationIssue(HomeLocationIssue.SIMULATION)
                    } else startRealLocation()
                }
            }
        }
    }

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
                    locationFlow = locationState,
                    onLocate = ::locate,
                    onLocationRecovery = ::recoverLocation,
                    onBrowse = ::browseMap,
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
        cameraPolicy = HomeCameraPolicy()
        locationState.value = HomeLocationState()
        map = view
        view.onCreate(null)
        mapModel.markedLoc?.let { point ->
            lastTarget = point
            lastAddress = mapModel.markName
            targetState.value = HomeTarget(point, mapModel.markName)
            cameraPolicy.userMovedCamera()
            val gcj = CoordinateTransform.toGcj02(Wgs84(point.first, point.second))
            targetMarker = view.marker(gcj, mapModel.markName ?: "待确认位置", selected = true)
            view.move(gcj, 16.5f)
            locationState.value = HomeLocationState(mapVisible = true)
        }
        if (mapModel.markedLoc == null) {
            mockModel.runtimeState.value.takeIf { it.isActive }?.sample?.let { sample ->
                cameraPolicy.userMovedCamera()
                view.move(CoordinateTransform.toGcj02(sample.coordinate), 16.5f)
                locationState.value = HomeLocationState(mapVisible = true, loading = false, issue = HomeLocationIssue.SIMULATION)
            }
        }
        view.onClick { point ->
            val wgs = CoordinateTransform.toWgs84(point)
            setTarget(wgs.latitude to wgs.longitude, null)
        }
        view.onStart()
        view.onResume()
        view.post {
            if (map === view && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startRealLocation()
        }
        return view
    }

    private fun openDrawer() {
        val activity = activity as? MainActivity ?: return
        activity.binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun setTarget(wgs: Pair<Double, Double>, name: String?) {
        cameraPolicy.userMovedCamera()
        locationState.value = locationState.value.copy(mapVisible = true)
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
            cameraPolicy.userMovedCamera()
            locationState.value = locationState.value.copy(mapVisible = true)
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
        startRealLocation()
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

    private fun hasLocationPermission() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    ).any { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }

    private fun showLocationIssue(issue: HomeLocationIssue) {
        locationTimeout?.cancel()
        locationState.value = locationState.value.copy(loading = false, issue = issue)
    }

    private fun recoverLocation() {
        when (locationState.value.issue) {
            HomeLocationIssue.PERMISSION -> {
                val requested = requireContext().getSharedPreferences("home_location", Context.MODE_PRIVATE)
                val deniedPermanently = requested.getBoolean("requested", false) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (deniedPermanently) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${requireContext().packageName}")))
                } else {
                    requested.edit().putBoolean("requested", true).apply()
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            HomeLocationIssue.DISABLED -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> locate()
        }
    }

    private fun browseMap() {
        cameraPolicy.userMovedCamera()
        // An explicit browse action uses a world overview, never an implied Beijing fix.
        map?.move(Gcj02(20.0, 0.0), 2f)
        locationState.value = locationState.value.copy(mapVisible = true)
    }

    private fun moveCamera(move: HomeCameraPolicy.Move) {
        map?.move(CoordinateTransform.toGcj02(Wgs84(move.position.latitude, move.position.longitude)), move.zoom,
            animate = locationState.value.mapVisible)
        locationState.value = locationState.value.copy(mapVisible = true)
    }

    private fun locate() {
        if (!hasLocationPermission()) { showLocationIssue(HomeLocationIssue.PERMISSION); return }
        if (mockModel.runtimeState.value.isActive) {
            mapModel.originalLocation?.let { (lat, lon) ->
                moveCamera(HomeCameraPolicy.Move(HomeCameraPolicy.Position(lat, lon)))
            }
            showLocationIssue(HomeLocationIssue.SIMULATION)
            return
        }
        cameraPolicy.locate(SystemClock.elapsedRealtime())?.let {
            moveCamera(it)
            locationState.value = locationState.value.copy(loading = false, issue = null)
            return
        }
        stopRealLocation()
        startRealLocation()
    }

    private fun acceptRealLocation(loc: Location, point: Pair<Double, Double>) {
        if (map == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val active = mockModel.runtimeState.value.isActive
        val age = if (loc.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        } else {
            // Some fused network fixes omit elapsed time; still require a recent timestamp.
            System.currentTimeMillis() - loc.time
        }
        if (loc.isMock || active || age !in 0..HomeCameraPolicy.MAX_FIX_AGE_MILLIS ||
            point.first !in -90.0..90.0 || point.second !in -180.0..180.0 ||
            point.first == 0.0 && point.second == 0.0) return
        val move = cameraPolicy.accept(point.first, point.second, age, SystemClock.elapsedRealtime(), loc.isMock, active)
        mapModel.currentLocation = point
        mapModel.rememberOriginalLocation(point, loc.isMock, active)
        map?.currentPosition(CoordinateTransform.toGcj02(Wgs84(point.first, point.second)))
        requireContext().lastKnownLat = point.first
        requireContext().lastKnownLng = point.second
        move?.let(::moveCamera)
        locationTimeout?.cancel()
        locationState.value = locationState.value.copy(loading = false, issue = null)
    }

    @Suppress("MissingPermission")
    private fun startRealLocation() {
        val map = map ?: return
        if (mockModel.runtimeState.value.isActive) { showLocationIssue(HomeLocationIssue.SIMULATION); return }
        if (!hasLocationPermission()) { showLocationIssue(HomeLocationIssue.PERMISSION); return }
        val manager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) { showLocationIssue(HomeLocationIssue.UNAVAILABLE); return }
        if (!manager.isLocationEnabled) { stopRealLocation(); showLocationIssue(HomeLocationIssue.DISABLED); return }
        if (amapLocation != null || locationListener != null) return
        locationState.value = locationState.value.copy(loading = true, issue = null)
        locationTimeout?.cancel()
        locationTimeout = viewLifecycleOwner.lifecycleScope.launch {
            delay(20_000)
            showLocationIssue(HomeLocationIssue.TIMEOUT)
        }
        systemLocation = manager
        // Consent is completed by MainActivity before this view is created. Only the selected
        // provider's client is initialized; Tencent never initializes AMap for positioning.
        if (map.provider == MapProvider.AMAP) {
            runCatching {
                AMapLocationClient(requireContext()).also { client ->
                    amapLocation = client
                    client.setLocationOption(AMapLocationClientOption().apply {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                        isOnceLocation = false
                        isNeedAddress = false
                        isLocationCacheEnable = false
                        interval = 1_000
                    })
                    client.setLocationListener { loc ->
                        if (amapLocation !== client || loc == null) return@setLocationListener
                        if (loc.errorCode == 0) acceptRealLocation(loc, loc.wgs84)
                    }
                    client.startLocation()
                }
            }.onFailure { stopRealLocation(); showLocationIssue(HomeLocationIssue.UNAVAILABLE) }
        } else {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (locationListener === this) acceptRealLocation(location, location.latitude to location.longitude)
                }
                override fun onProviderDisabled(provider: String) {
                    if (!manager.isLocationEnabled) showLocationIssue(HomeLocationIssue.DISABLED)
                }
                override fun onProviderEnabled(provider: String) {
                    if (!locationState.value.loading && locationState.value.issue != null) locate()
                }
            }
            locationListener = listener
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { it in manager.allProviders }
            val registered = providers.count { provider ->
                runCatching {
                    manager.requestLocationUpdates(provider, 1_000L, 0f, listener, android.os.Looper.getMainLooper())
                }.isSuccess
            }
            if (registered == 0) { stopRealLocation(); showLocationIssue(HomeLocationIssue.UNAVAILABLE) }
            // A recent system fix can avoid an unnecessary wait. Old and mock fixes are rejected.
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .sortedByDescending { it.elapsedRealtimeNanos }
                .firstOrNull { !it.isMock }?.let { acceptRealLocation(it, it.latitude to it.longitude) }
        }
    }

    private fun stopRealLocation() {
        locationTimeout?.cancel()
        locationTimeout = null
        val client = amapLocation
        amapLocation = null
        client?.let { runCatching { it.stopLocation(); it.onDestroy() } }
        locationListener?.let { listener -> runCatching { systemLocation?.removeUpdates(listener) } }
        locationListener = null
        systemLocation = null
    }
}

private enum class HomeLocationIssue(val message: String, val action: String) {
    PERMISSION("允许访问位置后，可将地图定位到你附近。也可直接搜索或选择坐标。", "授权位置"),
    DISABLED("系统定位已关闭，可开启定位或直接选择目标位置。", "开启定位"),
    TIMEOUT("暂时未获取到真实位置，请检查定位和网络，或移至开阔处重试。", "重新定位"),
    UNAVAILABLE("定位暂不可用，可重试或直接选择目标位置。", "重试"),
    SIMULATION("模拟期间不采集真实位置；已有真实位置可用于回到原处。", "返回真实位置"),
}

private data class HomeLocationState(
    val mapVisible: Boolean = false,
    val loading: Boolean = true,
    val issue: HomeLocationIssue? = null,
)

private data class HomeTarget(
    val point: Pair<Double, Double>? = null,
    val name: String? = null,
)

@Composable
private fun HomeScreen(
    mapModel: AMapViewModel,
    mockModel: MockServiceViewModel,
    targetFlow: kotlinx.coroutines.flow.Flow<HomeTarget>,
    locationFlow: kotlinx.coroutines.flow.StateFlow<HomeLocationState>,
    onLocate: () -> Unit,
    onLocationRecovery: () -> Unit,
    onBrowse: () -> Unit,
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
    val location by locationFlow.collectAsStateWithLifecycle()
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
        if (!location.mapVisible) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        }
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
            if (location.loading || location.issue != null || !location.mapVisible) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (location.loading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text("正在获取真实位置", style = MaterialTheme.typography.titleSmall)
                            }
                            Text("仅用于显示当前位置，不会修改模拟目标。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(location.issue?.message ?: "可搜索地点或直接浏览地图", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            location.issue?.let { issue ->
                                TextButton(onClick = onLocationRecovery) { Text(issue.action) }
                            }
                            if (!location.mapVisible) TextButton(onClick = onBrowse) { Text("先浏览地图") }
                        }
                    }
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
                FilledTonalButton(onClick = onLocate) { Text("定位") }
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
