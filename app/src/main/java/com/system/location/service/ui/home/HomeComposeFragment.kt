package com.system.location.service.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.system.location.service.maps.MapProvider
import com.system.location.service.maps.mapProvider
import com.system.location.service.ext.wgs84
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.runtime.ScenarioRuntime
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.system.location.service.ui.CallbackScheduler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.asPaddingValues
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
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.lastKnownLat
import com.system.location.service.ext.lastKnownLng
import androidx.navigation.fragment.findNavController
import androidx.compose.runtime.saveable.rememberSaveable
import com.system.location.service.core.repository.SavedRoute
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.data.repository.toHistorical
import com.system.location.service.maps.MapLine
import com.system.location.service.maps.MapLocationBadge
import com.system.location.service.maps.MapServices
import com.system.location.service.maps.PlaceSuggestion
import com.system.location.service.maps.PortalMapView

import com.system.location.service.maps.satelliteMap
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
    private val libraryState = kotlinx.coroutines.flow.MutableStateFlow(HomeLibraryState())
    private var libraryLoad: Job? = null
    private var libraryRequest = 0L
    private var startInFlight = false
    private var selectionRevision = 0L
    private var restoredKind: String? = null
    private var restoredId: String? = null
    private var map: PortalMapView? = null
    private var targetMarker: com.system.location.service.maps.MapOverlay? = null
    private var routeLine: MapLine? = null
    private var systemLocation: LocationManager? = null
    private var locationListener: android.location.LocationListener? = null

    private var cameraPolicy = HomeCameraPolicy()
    private val locationState = kotlinx.coroutines.flow.MutableStateFlow(HomeLocationState())
    private var amapLocation: AMapLocationClient? = null
    private var locationTimeout: Job? = null
    private var providerReceiverRegistered = false
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) startRealLocation()
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) locate() else showLocationIssue(HomeLocationIssue.PERMISSION)
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (com.system.location.service.android.window.OverlayUtils.hasOverlayPermissions(requireContext())) {
            if (!RockerOverlay.setEnabled(true)) {
                Toast.makeText(requireContext(), "悬浮窗显示失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "未授予悬浮窗权限，可再次点击摇杆授权", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredKind = savedInstanceState?.getString("home.target.kind")
        restoredId = savedInstanceState?.getString("home.target.id")
        if (restoredKind == "point" && restoredId == null && savedInstanceState != null) {
            val point = Wgs84(savedInstanceState.getDouble("home.target.lat"), savedInstanceState.getDouble("home.target.lon"))
            targetState.value = HomeTarget(HomeSelection.Point(point, savedInstanceState.getString("home.target.name")))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mockModel.runtimeState.collect { runtime ->
                    renderRuntimePosition()
                    if (runtime.isActive || startInFlight) {
                        stopRealLocation()
                        locationState.value = locationState.value.copy(loading = false, issue = null)
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
                    mockModel = mockModel,
                    targetFlow = targetState,
                    locationFlow = locationState,
                    libraryFlow = libraryState,
                    onLocate = ::locate,
                    onLocationRecovery = ::recoverLocation,
                    onBrowse = ::browseMap,
                    createMap = ::createMap,
                    onMenu = ::openDrawer,
                    onTarget = ::setTarget,
                    onSearch = ::search,
                    onSaveLocation = ::saveLocation,
                    onRocker = ::toggleRocker,
                    onOpenTargets = ::refreshLibrary,
                    onSelection = ::selectTarget,
                    onStart = ::startSelected,
                    onCreateRoute = { findNavController().navigate(R.id.nav_route_edit) },
                    onMapType = ::toggleMapType,
                )
            }
        }
    }

    private fun createMap(context: Context): View {
        map?.let { existing ->
            if (existing.provider == context.mapProvider) {
                // The home Fragment survives on the back stack. Reattach its paused map rather
                // than synchronously initializing the vendor SDK on every return from settings.
                (existing.parent as? ViewGroup)?.removeView(existing)
                existing.satellite(context.satelliteMap)
                existing.onStart()
                existing.onResume()
                renderRuntimePosition()
                return existing
            }
            releaseMap()
        }
        val view = PortalMapView(context)
        cameraPolicy = HomeCameraPolicy()
        locationState.value = HomeLocationState()
        map = view
        view.onCreate(null)
        if (targetState.value.selection == null && restoredKind == null) mapModel.markedLoc?.let { point ->
            targetState.value = HomeTarget(HomeSelection.Point(Wgs84(point.first, point.second), mapModel.markName))
        }
        renderSelection(moveCamera = true)
        renderRuntimePosition()
        if (targetState.value.selection == null && mapModel.markedLoc == null) {
            mockModel.runtimeState.value.takeIf { it.isActive }?.sample?.let { sample ->
                cameraPolicy.userMovedCamera()
                view.move(CoordinateTransform.toGcj02(sample.coordinate), 16.5f)
                locationState.value = locationState.value.copy(mapVisible = true, loading = false)
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
        selectTarget(HomeSelection.Point(Wgs84(wgs.first, wgs.second), name))
        val revision = selectionRevision
        val map = map ?: return
        val gcj = CoordinateTransform.toGcj02(Wgs84(wgs.first, wgs.second))
        viewLifecycleOwner.lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                runCatching { MapServices(map.context.applicationContext, map.provider).address(gcj) }.getOrNull()
            }
            if (address != null && selectionRevision == revision) {
                mapModel.markName = address.title.ifBlank { address.address }
                targetState.value = targetState.value.copy(selection = HomeSelection.Point(Wgs84(wgs.first, wgs.second), mapModel.markName))
            }
        }
    }

    private fun selectTarget(selection: HomeSelection) {
        selectionRevision++
        restoredKind = null
        restoredId = null
        targetState.value = HomeTarget(selection, busy = startInFlight)
        renderSelection(moveCamera = true)
    }

    private fun renderSelection(moveCamera: Boolean = false) {
        val selected = targetState.value.selection
        routeLine?.remove()
        routeLine = null
        targetMarker?.remove()
        targetMarker = null
        val point = when (selected) {
            is HomeSelection.Point -> selected.coordinate
            is HomeSelection.Route -> selected.saved.route.points.first()
            null -> null
        }
        mapModel.markedLoc = (selected as? HomeSelection.Point)?.coordinate?.let { it.latitude to it.longitude }
        mapModel.markName = (selected as? HomeSelection.Point)?.name

        if (point == null) return
        cameraPolicy.userMovedCamera()
        locationState.value = locationState.value.copy(mapVisible = true)
        val map = map ?: return
        val gcj = CoordinateTransform.toGcj02(point)
        targetMarker = map.marker(gcj, targetState.value.name ?: "待确认位置",
            label = MapLocationBadge(map.context, false, "模拟目标"), anchorY = 44f / 64f)
        if (selected is HomeSelection.Route) {
            val geometry = selected.saved.route.points.map(CoordinateTransform::toGcj02)
            routeLine = map.line(geometry, Color.argb(200, 59, 130, 246), 12f)
            if (moveCamera) map.fit(geometry, (72 * resources.displayMetrics.density).toInt())
        } else if (moveCamera) map.move(gcj, 16.5f, animate = true)
    }

    private fun refreshLibrary() {
        val request = ++libraryRequest
        libraryLoad?.cancel()
        val revision = selectionRevision
        libraryState.value = libraryState.value.copy(busy = true, error = null)
        targetState.value = targetState.value.copy(busy = true)
        libraryLoad = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    LibraryRepositories.ensureMigrated()
                    HomeLibraryState(
                        routes = LibraryRepositories.routes.list().sortedWith(compareByDescending<SavedRoute> { it.favorite }.thenBy { it.route.name }),
                        locations = LibraryRepositories.locations.list().sortedBy { it.name },
                    )
                }
                libraryState.value = loaded
                if (selectionRevision == revision) {
                    val old = targetState.value.selection
                    val selected = when (restoredKind) {
                        "route" -> loaded.routes.firstOrNull { it.route.id == restoredId }?.let(HomeSelection::Route)
                        "point" -> if (restoredId != null) loaded.locations.firstOrNull { it.id == restoredId }
                            ?.let { HomeSelection.Point(it.coordinate, it.name, it.id) } else old
                        else -> old?.refreshed(loaded.routes, loaded.locations)
                    }
                    val missing = selected == null && (old != null || restoredId != null)
                    restoredKind = null
                    restoredId = null
                    targetState.value = HomeTarget(selected, busy = startInFlight, message = if (missing) "已选目标已被删除，请重新选择" else null)
                    renderSelection(moveCamera = old != selected)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                libraryState.value = libraryState.value.copy(busy = false, error = "资料库加载失败，请重试")
                if (selectionRevision == revision) targetState.value = targetState.value.copy(busy = startInFlight, message = "资料库加载失败，请重新选择或重试")
            } finally {
                if (libraryRequest == request && selectionRevision == revision) targetState.value = targetState.value.copy(busy = startInFlight)
            }
        }
    }

    private fun startSelected() {
        val selected = targetState.value.selection ?: return
        if (startInFlight || targetState.value.busy) return
        startInFlight = true
        stopRealLocation()
        val revision = selectionRevision
        targetState.value = targetState.value.copy(busy = true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val current = withContext(Dispatchers.IO) {
                    LibraryRepositories.ensureMigrated()
                    when (selected) {
                        is HomeSelection.Route -> LibraryRepositories.routes.get(selected.saved.route.id)?.let(HomeSelection::Route)
                        is HomeSelection.Point -> if (selected.savedId == null) selected else LibraryRepositories.locations.get(selected.savedId)
                            ?.let { HomeSelection.Point(it.coordinate, it.name, it.id) }
                    }
                }
                if (revision != selectionRevision) return@launch
                targetState.value = HomeTarget(current, busy = true,
                    message = if (current == null) "已选目标已被删除，请重新选择" else null)
                renderSelection()
                current?.let {
                    val started = it.start(
                        point = { point -> mockModel.startPoint(point.coordinate.latitude to point.coordinate.longitude, point.name ?: "目标位置") },
                        route = { route -> mockModel.startRoute(route.toHistorical()) },
                    )
                    if (!started) Toast.makeText(requireContext(), mockModel.failureMessage(), Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision == selectionRevision) targetState.value = targetState.value.copy(message = "启动失败，请重试")
            } finally {
                startInFlight = false
                targetState.value = targetState.value.copy(busy = libraryState.value.busy)
                if (!mockModel.runtimeState.value.isActive) startRealLocation()
            }
        }
    }

    private fun search(query: String, result: (List<PlaceSuggestion>) -> Unit) {
        if (query.isBlank()) return
        val map = map ?: return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = try {
                withContext(Dispatchers.IO) { MapServices(appContext, map.provider).suggestions(query) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            result(suggestions)
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
        if (!com.system.location.service.android.window.OverlayUtils.hasOverlayPermissions(requireContext())) {
            Toast.makeText(requireContext(), "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
            runCatching {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${requireContext().packageName}")))
            }.onFailure {
                Toast.makeText(requireContext(), "无法打开设置，请在系统设置中允许 Portal 显示悬浮窗", Toast.LENGTH_LONG).show()
            }
        } else if (RockerOverlay.isVisible) {
            RockerOverlay.setEnabled(false)
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

    override fun onStart() {
        super.onStart()
        map?.onStart()
        if (!providerReceiverRegistered) {
            ContextCompat.registerReceiver(requireContext(), providerReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
            providerReceiverRegistered = true
        }
        startRealLocation()
        refreshLibrary()
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
        if (providerReceiverRegistered) {
            requireContext().unregisterReceiver(providerReceiver)
            providerReceiverRegistered = false
        }
        stopRealLocation()
        map?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        map?.onSaveInstanceState(outState)
        val selected = targetState.value.selection
        outState.putString("home.target.kind", when (selected) {
            is HomeSelection.Route -> "route"
            is HomeSelection.Point -> "point"
            null -> restoredKind
        })
        outState.putString("home.target.id", when (selected) {
            is HomeSelection.Route -> selected.saved.route.id
            is HomeSelection.Point -> selected.savedId
            null -> restoredId
        })
        if (selected is HomeSelection.Point) {
            outState.putDouble("home.target.lat", selected.coordinate.latitude)
            outState.putDouble("home.target.lon", selected.coordinate.longitude)
            outState.putString("home.target.name", selected.name)
        }
    }

    override fun onDestroyView() {
        libraryLoad?.cancel()
        stopRealLocation()
        // onPause/onStop suspend rendering and location requests before the view is removed.
        // Keep only this Fragment's map; Activity recreation and Fragment removal release it.
        (map?.parent as? ViewGroup)?.removeView(map)
        super.onDestroyView()
    }

    override fun onDestroy() {
        releaseMap()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (view == null) releaseMap()
    }

    private fun releaseMap() {
        map?.onDestroy()
        map = null
        targetMarker = null
        routeLine = null
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
        val runtime = mockModel.runtimeState.value
        if (runtime.isActive) {
            runtime.sample?.coordinate?.let { point ->
                cameraPolicy.userMovedCamera()
                map?.currentPosition(CoordinateTransform.toGcj02(point), simulated = true)
                moveCamera(HomeCameraPolicy.Move(HomeCameraPolicy.Position(point.latitude, point.longitude)))
            } ?: Toast.makeText(requireContext(), "正在等待模拟位置", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasLocationPermission()) { stopRealLocation(); showLocationIssue(HomeLocationIssue.PERMISSION); return }
        cameraPolicy.locate(SystemClock.elapsedRealtime())?.let {
            moveCamera(it)
            locationState.value = locationState.value.copy(loading = false, issue = null)
            return
        }
        stopRealLocation()
        startRealLocation()
    }

    private fun renderRuntimePosition() {
        val view = map ?: return
        val runtime = mockModel.runtimeState.value
        val original = mapModel.originalLocation?.let { (lat, lon) -> CoordinateTransform.toGcj02(Wgs84(lat, lon)) }
        view.originalPosition(if (runtime.isActive) original else null)
        if (runtime.isActive) {
            val sample = runtime.sample
            if (sample != null) {
                view.currentPosition(CoordinateTransform.toGcj02(sample.coordinate), simulated = true)
                locationState.value = locationState.value.copy(mapVisible = true, loading = false, issue = null)
            } else view.clearCurrentPosition()
        } else {
            mapModel.currentLocation?.let { (lat, lon) ->
                view.currentPosition(CoordinateTransform.toGcj02(Wgs84(lat, lon)))
            } ?: view.clearCurrentPosition()
        }
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
        if (mockModel.runtimeState.value.isActive || startInFlight) {
            stopRealLocation()
            locationState.value = locationState.value.copy(loading = false, issue = null)
            return
        }
        if (!hasLocationPermission()) { stopRealLocation(); showLocationIssue(HomeLocationIssue.PERMISSION); return }
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
}

private data class HomeLocationState(
    val mapVisible: Boolean = false,
    val loading: Boolean = true,
    val issue: HomeLocationIssue? = null,
)

private data class HomeTarget(
    val selection: HomeSelection? = null,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val point get() = (selection as? HomeSelection.Point)?.coordinate?.let { it.latitude to it.longitude }
    val name get() = when (selection) {
        is HomeSelection.Point -> selection.name
        is HomeSelection.Route -> selection.saved.route.name
        null -> null
    }
}

/** Consistent 48 dp touch targets with 24 dp vectors, independent of font scaling. */
@Composable
private fun HomeMapButton(icon: Int, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun HomeScreen(
    mockModel: MockServiceViewModel,
    targetFlow: kotlinx.coroutines.flow.Flow<HomeTarget>,
    locationFlow: kotlinx.coroutines.flow.StateFlow<HomeLocationState>,
    libraryFlow: kotlinx.coroutines.flow.StateFlow<HomeLibraryState>,
    onLocate: () -> Unit,
    onLocationRecovery: () -> Unit,
    onBrowse: () -> Unit,
    createMap: (Context) -> View,
    onMenu: () -> Unit,
    onTarget: (Pair<Double, Double>, String?) -> Unit,
    onSearch: (String, (List<PlaceSuggestion>) -> Unit) -> Unit,
    onSaveLocation: (String, String, Double, Double) -> Unit,
    onRocker: () -> Unit,
    onOpenTargets: () -> Unit,
    onSelection: (HomeSelection) -> Unit,
    onStart: () -> Unit,
    onCreateRoute: () -> Unit,
    onMapType: () -> Boolean,
) {
    val context = LocalContext.current
    val runtimeFlow = remember(mockModel) {
        mockModel.runtimeState.map(HomeRuntimeUi::from).distinctUntilChanged()
    }
    val runtime by runtimeFlow.collectAsStateWithLifecycle(initialValue = HomeRuntimeUi.from(mockModel.runtimeState.value))
    val location by locationFlow.collectAsStateWithLifecycle()
    val target by targetFlow.collectAsStateWithLifecycle(initialValue = HomeTarget())
    val library by libraryFlow.collectAsStateWithLifecycle()
    var targetPicker by rememberSaveable { mutableStateOf(false) }
    val queryState = remember { mutableStateOf("") }
    var query by queryState
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var saveDialog by remember { mutableStateOf(false) }
    var coordinateDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var saveAddress by remember { mutableStateOf("") }
    var latInput by remember { mutableStateOf("") }
    var lonInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requestSearch by rememberUpdatedState(onSearch)
    val search = remember(lifecycle, context) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        DebouncedSearch<List<PlaceSuggestion>>(
            scheduler = object : CallbackScheduler {
                override fun post(delayMillis: Long, callback: Runnable) { handler.postDelayed(callback, delayMillis) }
                override fun remove(callback: Runnable) { handler.removeCallbacks(callback) }
            },
            query = { queryState.value },
            viewActive = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            request = { text, reply -> requestSearch(text, reply) },
            render = { result ->
                suggestions = result
                if (result.isEmpty()) Toast.makeText(context, "未找到地点，请修改搜索词后重试", Toast.LENGTH_SHORT).show()
            },
        )
    }
    DisposableEffect(search, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) search.select { suggestions = emptyList() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); search.destroy() }
    }
    fun dismissSearch() { search.select { suggestions = emptyList() } }

    if (targetPicker) HomeTargetPicker(
        library = library,
        onSelect = { onSelection(it); targetPicker = false },
        onDismiss = { targetPicker = false },
        onRetry = onOpenTargets,
        onCreateRoute = { targetPicker = false; onCreateRoute() },
    )

    if (suggestions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = ::dismissSearch,
            title = { Text("选择地点") },
            text = {
                LazyColumn(Modifier.height(300.dp)) {
                    items(suggestions) { suggestion ->
                        TextButton(onClick = {
                            search.select { suggestions = emptyList(); query = suggestion.name }
                            val wgs = CoordinateTransform.toWgs84(suggestion.point)
                            onTarget(wgs.latitude to wgs.longitude, suggestion.name)
                        }) { Text("${suggestion.name}\n${suggestion.address}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::dismissSearch) { Text("取消") } },
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

    var bottomPanelHeight by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val mapBottomInset = bottomPanelHeight + with(density) {
        (24.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()).roundToPx()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 500.dp
        AndroidView(
            factory = { ctx -> createMap(ctx) },
            update = {
                it.visibility = if (location.mapVisible) View.VISIBLE else View.INVISIBLE
                (it as PortalMapView).setBottomObstruction(mapBottomInset)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!location.mapVisible) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, end = if (compact) 80.dp else 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMenu) {
                        Icon(painterResource(R.drawable.ic_home_menu), contentDescription = "打开菜单")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { text -> search.select { query = text; suggestions = emptyList() } },
                        placeholder = { Text("搜索") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search.submit() }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { search.submit() }, enabled = query.isNotBlank()) {
                        Icon(painterResource(R.drawable.ic_home_search), contentDescription = "搜索地点")
                    }
                }
            }
            if (!runtime.isActive && (location.loading || location.issue != null || !location.mapVisible)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    if (compact) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (location.loading) "正在获取真实位置" else location.issue?.message ?: "可直接浏览地图",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        location.issue?.let { issue -> TextButton(onClick = onLocationRecovery) { Text(issue.action) } }
                        if (!location.mapVisible) TextButton(onClick = onBrowse) { Text("先浏览地图") }
                    } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            PortalCard(
                modifier = Modifier
                    .testTag("home-runtime-panel")
                    .onSizeChanged { bottomPanelHeight = it.height },
                contentPadding = PaddingValues(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            target.name ?: "目标位置",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (target.selection as? HomeSelection.Route)?.saved?.let { "${it.route.points.size} 个点 · ${it.mode.homeLabel()}" }
                                ?: target.point?.let { (lat, lon) -> String.format(Locale.ROOT, "%.6f, %.6f", lat, lon) }
                                ?: "点击地图选择位置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { saveDialog = true }, enabled = target.point != null) {
                        Icon(painterResource(R.drawable.ic_home_save), contentDescription = "保存位置")
                    }
                    IconButton(onClick = onRocker) {
                        Icon(painterResource(R.drawable.ic_home_rocker),
                            contentDescription = if (RockerOverlay.isVisible) "关闭悬浮窗" else "打开悬浮窗")
                    }
                }
                target.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                HomeRuntimeControls(
                    runtime = runtime,
                    canStart = target.selection != null && !target.busy,
                    onStart = onStart,
                    onPauseResume = {
                        if (runtime.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume()
                        else ScenarioRuntime.pause()
                    },
                    onStop = { scope.launch { mockModel.stopScenario() } },
                )
            }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 16.dp)
                .testTag("home-map-tools"),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeMapButton(R.drawable.ic_home_layers, "切换地图图层") { onMapType() }
            HomeMapButton(R.drawable.ic_home_route, "选择模拟目标") {
                targetPicker = true
                onOpenTargets()
            }
            HomeMapButton(R.drawable.ic_home_coordinates, "输入坐标") { coordinateDialog = true }
            HomeMapButton(R.drawable.ic_home_locate, if (runtime.isActive) "定位到模拟位置" else "定位到真实位置", onLocate)
        }

    }
}
