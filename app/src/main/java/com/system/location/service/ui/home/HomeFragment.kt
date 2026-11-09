package com.system.location.service.ui.home
import com.system.location.service.ui.displayStates
import com.system.location.service.ui.setTextIfChanged

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.system.location.service.maps.*
import com.system.location.service.core.geo.Gcj02
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.ui.mock.RockerOverlay
import com.system.location.service.android.window.OverlayUtils
import com.system.location.service.bdmap.Poi
import com.system.location.service.databinding.FragmentHomeBinding
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.lastKnownLat
import com.system.location.service.ext.lastKnownLng
import com.system.location.service.ext.selectLocation
import com.system.location.service.ext.selectRoute
import com.system.location.service.ext.wgs84
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import com.system.location.service.ui.CallbackScheduler
import java.math.BigDecimal
import java.util.Locale

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val aMapViewModel by activityViewModels<AMapViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    private lateinit var mLocationClient: AMapLocationClient
    private var searchCoordinator: DebouncedSearch<Result<List<PlaceSuggestion>>>? = null
    private var hasCenteredInitialLocation = false
    private var selectedMarker: MapOverlay? = null
    private var originalMarker: MapOverlay? = null
    private var routePreview: MapLine? = null
    private var isApplyingLocation = false
    private var addressJob: Job? = null
    private var addressGeneration = 0L
    private var systemLocation: android.location.LocationManager? = null
    private var systemListener: android.location.LocationListener? = null
    private var displayingSimulation = false
    private var realLifecycle = RealPositionLifecycle(::realPositioning)
    private var realPositionFilter = PositionFilter()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        realPositionFilter = PositionFilter()
        realLifecycle = RealPositionLifecycle(::realPositioning)
        binding.amapView.onCreate(savedInstanceState)
        val root: View = binding.root
        val search = binding.topSearchBarContainer
        val searchLeft = search.paddingLeft
        val searchTop = search.paddingTop
        val searchRight = search.paddingRight
        val actions = binding.mapActions
        val actionMargins = actions.layoutParams as ViewGroup.MarginLayoutParams
        val actionTop = actionMargins.topMargin
        val actionRight = actionMargins.rightMargin
        val actionLeft = actionMargins.leftMargin
        val bottom = binding.bottomControlCard
        val bottomMargins = bottom.layoutParams as ViewGroup.MarginLayoutParams
        val bottomLeft = bottomMargins.leftMargin
        val bottomRight = bottomMargins.rightMargin
        val bottomSpace = bottomMargins.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            search.updatePadding(left = searchLeft + safe.left, top = searchTop + safe.top, right = searchRight + safe.right)
            actions.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = actionTop + safe.top
                leftMargin = actionLeft + safe.left
                rightMargin = actionRight + safe.right
            }
            bottom.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bottomLeft + safe.left
                rightMargin = bottomRight + safe.right
                bottomMargin = bottomSpace + maxOf(safe.bottom, keyboard.bottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)

        aMapViewModel.isExists = true
        setupMap()
        setupLocationClient()
        setupSearchBar()
        setupQuickActions()
        setupBottomPanel()

        return root
    }

    private fun setupMap() {
        val lat = requireContext().lastKnownLat
        val lng = requireContext().lastKnownLng
        if (lat != 0.0 && lng != 0.0) {
            binding.amapView.move((lat to lng).mapPoint, 16f)
            hasCenteredInitialLocation = true
        }
        binding.amapView.onClick { onTargetLocationUpdated(it.storedPoint, true) }
        renderOriginalPosition()
    }

    private fun setupLocationClient() {
        if (binding.amapView.provider == MapProvider.TENCENT) {
            systemLocation = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            systemListener = android.location.LocationListener { loc ->
                if (_binding == null || !realLifecycle.acceptsFix() || displayingSimulation || mockServiceViewModel.runtimeState.value.isActive || loc.isMock) return@LocationListener
                val wgs = realPositionFilter.accept(loc.latitude to loc.longitude, loc.time, loc.accuracy, loc.speed) ?: return@LocationListener
                if (aMapViewModel.rememberOriginalLocation(wgs, loc.isMock, false)) renderOriginalPosition()
                showCurrentPosition(wgs, loc.accuracy)
                requireContext().lastKnownLat = wgs.first; requireContext().lastKnownLng = wgs.second
                if (!hasCenteredInitialLocation) { hasCenteredInitialLocation = true; binding.amapView.move(wgs.mapPoint, 16.5f, true) }
            }
            return
        }
        mLocationClient = AMapLocationClient(requireContext())
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isNeedAddress = true
            isSensorEnable = false
            isGpsFirst = false
            isLocationCacheEnable = false
            interval = 1000
        }
        mLocationClient.setLocationOption(option)
        mLocationClient.setLocationListener { loc ->
            if (_binding == null || !realLifecycle.acceptsFix() || displayingSimulation || mockServiceViewModel.runtimeState.value.isActive || loc == null || loc.errorCode != 0 || loc.isMock) return@setLocationListener

            val wgs = realPositionFilter.accept(loc.wgs84, loc.time, loc.accuracy, loc.speed) ?: return@setLocationListener
            if (aMapViewModel.rememberOriginalLocation(wgs, loc.isMock, mockServiceViewModel.runtimeState.value.isActive)) {
                renderOriginalPosition()
            }
            showCurrentPosition(wgs, loc.accuracy)
            requireContext().lastKnownLat = wgs.first
            requireContext().lastKnownLng = wgs.second

            if (!loc.city.isNullOrBlank()) {
                MainActivity.mCityString = loc.city
            }

            // Fly smoothly to user's real location on first fix if not already positioned
            if (!hasCenteredInitialLocation) {
                hasCenteredInitialLocation = true
                binding.amapView.move(wgs.mapPoint, 16.5f, true)
            }
        }
        // The resumed-state collector checks simulation before starting real positioning.
    }

    private fun renderOriginalPosition() {
        if (_binding == null) return
        val original = aMapViewModel.originalLocation
        if (original == null || originalMarker != null) return
        val label = TextView(requireContext()).apply {
            text = "● 原始位置"
            setTextColor(Color.WHITE)
            textSize = 13f
            val padding = (8 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(5, 120, 90)); cornerRadius = padding.toFloat()
            }
        }
        originalMarker = binding.amapView.marker(original.mapPoint, "本次打开应用时的原始位置", label)
    }

    private fun showCurrentPosition(wgs: Pair<Double, Double>, accuracy: Float) {
        aMapViewModel.currentLocation = wgs
        binding.amapView.currentPosition(wgs.mapPoint)
    }

    @SuppressLint("MissingPermission")
    private fun realPositioning(start: Boolean) {
        if (::mLocationClient.isInitialized) {
            if (start && !mLocationClient.isStarted) { realPositionFilter = PositionFilter(); mLocationClient.startLocation() }
            else if (!start && mLocationClient.isStarted) mLocationClient.stopLocation()
        }
        val listener = systemListener ?: return
        systemLocation?.removeUpdates(listener)
        if (start) {
            realPositionFilter = PositionFilter()
            try {
                for (provider in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                    // Register disabled providers too, so enabling Location can deliver fixes
                    // without requiring the Fragment to pause and resume first.
                    if (systemLocation?.allProviders?.contains(provider) == true) systemLocation?.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                }
            } catch (error: SecurityException) { Log.w("HomeFragment", "Location permission unavailable") }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        aMapViewModel.markedLoc?.let { onTargetLocationUpdated(it, true) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                mockServiceViewModel.runtimeState.displayStates().collect { state ->
                    val running = state.isActive
                    val position = state.sample?.coordinate?.let { it.latitude to it.longitude }
                    updateMockButtonState()
                    displayingSimulation = running
                    renderOriginalPosition()
                    realLifecycle.simulation(running)
                    if (running) position?.let { showCurrentPosition(it, requireContext().accuracy) }
                }
            }
        }
    }

    private fun setupSearchBar() {
        val ui = binding
        val handler = Handler(Looper.getMainLooper())
        val search = DebouncedSearch<Result<List<PlaceSuggestion>>>(
            scheduler = object : CallbackScheduler {
                override fun post(delayMillis: Long, callback: Runnable) { handler.postDelayed(callback, delayMillis) }
                override fun remove(callback: Runnable) { handler.removeCallbacks(callback) }
            },
            query = { ui.etSearchQuery.text?.toString().orEmpty() },
            viewActive = { _binding === ui && isAdded && viewLifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED },
            request = { query, complete ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try { complete(Result.success(MapServices(requireContext(), ui.amapView.provider).suggestions(query, MainActivity.mCityString.orEmpty()))) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { complete(Result.failure(error)) }
                }
            },
            render = { result -> renderInputTips(result) },
        )
        searchCoordinator = search
        // Menu button opens the drawer in MainActivity
        binding.btnMenu.setOnClickListener {
            val mainActivity = activity as? MainActivity
            val drawer = mainActivity?.binding?.drawerLayout
            drawer?.openDrawer(GravityCompat.START)
        }

        binding.btnClearSearch.setOnClickListener {
            search.select { binding.etSearchQuery.setText("") }
            binding.cardSearchResults.visibility = View.GONE
        }

        binding.etSearchQuery.addTextChangedListener { text ->
            val query = text?.toString()?.trim() ?: ""
            binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            binding.cardSearchResults.visibility = View.GONE
            search.textChanged()
        }

        binding.btnDoSearch.setOnClickListener {
            search.submit()
        }

        binding.lvSearchResults.setOnItemClickListener { _, view, _, _ ->
            val lngText = (view.findViewById<View>(R.id.poi_longitude) as? TextView)?.text?.toString()
            val latText = (view.findViewById<View>(R.id.poi_latitude) as? TextView)?.text?.toString()
            val nameText = (view.findViewById<View>(R.id.poi_name) as? TextView)?.text?.toString()

            val lng = lngText?.toDoubleOrNull()
            val lat = latText?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val wgs = lat to lng
                aMapViewModel.markedLoc = wgs
                onTargetLocationUpdated(wgs, true)
                aMapViewModel.markName = nameText
                binding.tvTargetName.text = nameText ?: "已选位置"

                binding.cardSearchResults.visibility = View.GONE
                search.select { binding.etSearchQuery.setText(nameText ?: "") }
            }
        }
    }

    private fun renderInputTips(result: Result<List<PlaceSuggestion>>) {
        val ui = _binding ?: return
        val candidates = result.getOrElse {
            ui.cardSearchResults.visibility = View.GONE
            Toast.makeText(requireContext(), it.message ?: "地点搜索失败", Toast.LENGTH_LONG).show()
            return
        }
        if (candidates.isEmpty()) { ui.cardSearchResults.visibility = View.GONE; return }
        val data = candidates.map { place ->
            val point = place.point.storedPoint
            mapOf(Poi.KEY_NAME to place.name, Poi.KEY_ADDRESS to place.address,
                Poi.KEY_LONGITUDE_RAW to point.second.toString(), Poi.KEY_LATITUDE_RAW to point.first.toString(), Poi.KEY_TAG to "")
        }
        ui.lvSearchResults.adapter = SimpleAdapter(requireContext(), data, R.layout.layout_search_poi_item,
            arrayOf(Poi.KEY_NAME, Poi.KEY_ADDRESS, Poi.KEY_LONGITUDE_RAW, Poi.KEY_LATITUDE_RAW, Poi.KEY_TAG),
            intArrayOf(R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude, R.id.poi_tag))
        ui.cardSearchResults.visibility = View.VISIBLE
    }

    private fun setupQuickActions() {
        // Toggle Normal / Satellite View
        binding.fabMapType.setOnClickListener {
            val next = !requireContext().satelliteMap
            binding.amapView.satellite(next)
            requireContext().satelliteMap = next

        }

        // Toggle Route Preview
        var isRouteShown = false
        binding.fabToggleRoute.setOnClickListener {
            isRouteShown = !isRouteShown
            if (isRouteShown) {
                val route = requireContext().selectRoute?.route
                if (!route.isNullOrEmpty()) {
                    previewRoute(route)
                    binding.amapView.move(route.first().mapPoint, 16f, true)
                    binding.fabToggleRoute.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.primary)
                    )
                    Toast.makeText(requireContext(), "已加载选中路线", Toast.LENGTH_SHORT).show()
                } else {
                    isRouteShown = false
                    Toast.makeText(requireContext(), "未选择路线，请先在路线库中选取", Toast.LENGTH_SHORT).show()
                }
            } else {
                routePreview?.remove()
                routePreview = null
                binding.fabToggleRoute.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.icon_grey)
                )
            }
        }

        // Direct Coordinate Input Dialog
        binding.fabInputCoords.setOnClickListener {
            showInputCoordinatesDialog()
        }

        // Center on Current Real Location
        binding.fabMyLocation.setOnClickListener {
            val cur = aMapViewModel.currentLocation
            if (cur != null) {
                binding.amapView.move(cur.mapPoint, animate = true)
            } else {
                Toast.makeText(requireContext(), "正在等待真实定位", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBottomPanel() {
        updateMockButtonState()
        binding.btnApplyLocation.setOnClickListener { tryOpenMock() }
        binding.targetSelection.setOnClickListener { tryOpenMock() }

        binding.btnQuickMock.setOnClickListener { tryCloseMock() }

        // Toggle Floating Rocker
        binding.btnQuickRocker.setOnClickListener {
            if (RockerOverlay.isEnabled) {
                RockerOverlay.setEnabled(false)
                updateMockButtonState()
                return@setOnClickListener
            }
            if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
                Toast.makeText(requireContext(), "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!RockerOverlay.setEnabled(true)) {
                Toast.makeText(requireContext(), "悬浮窗显示失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
            }
            updateMockButtonState()
        }

        // Save location
        binding.btnSaveLocation.setOnClickListener {
            showAddLocationDialog()
        }
    }

    private fun tryOpenMock() {
        if (isApplyingLocation) return
        val target = aMapViewModel.markedLoc
        if (target == null) {
            Toast.makeText(requireContext(), "请先在地图上选择位置", Toast.LENGTH_SHORT).show()
            return
        }
        val name = aMapViewModel.markName ?: "目标位置"
        val hist = HistoricalLocation(name, name, target.first, target.second)
        requireContext().selectLocation = hist
        mockServiceViewModel.selectedLocation = hist

        val accuracy = requireContext().accuracy
        isApplyingLocation = true
        updateMockButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            val started = mockServiceViewModel.startPoint(target, name)
            isApplyingLocation = false
            if (_binding == null) return@launch
            displayingSimulation = started
            if (started) showCurrentPosition(target, accuracy)
            updateMockButtonState()
            Toast.makeText(requireContext(), if (started) "已切换到所选位置" else mockServiceViewModel.failureMessage(), Toast.LENGTH_LONG).show()
        }
    }

    private fun tryCloseMock() {
        lifecycleScope.launch {
            val stopped = mockServiceViewModel.stopScenario()
            if (_binding == null) return@launch
            if (!stopped) {
                Toast.makeText(requireContext(), mockServiceViewModel.failureMessage(), Toast.LENGTH_LONG).show()
                return@launch
            }
            updateMockButtonState()
            Toast.makeText(requireContext(), "模拟已停止", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMockButtonState() {
        if (_binding == null) return
        val isRunning = mockServiceViewModel.isServiceStart()
        binding.btnApplyLocation.isEnabled = aMapViewModel.markedLoc != null && !isApplyingLocation
        binding.btnApplyLocation.setTextIfChanged(if (isApplyingLocation) "正在切换…" else if (isRunning) "切换位置" else "开始模拟")
        binding.btnQuickMock.isEnabled = !isApplyingLocation && isRunning
        if (binding.btnQuickMock.text != "停止模拟") {
            binding.btnQuickMock.text = "停止模拟"
            binding.btnQuickMock.setIconResource(R.drawable.baseline_stop_24)
        }
        binding.btnQuickRocker.setTextIfChanged(if (RockerOverlay.isEnabled) "关闭悬浮窗" else "开启悬浮窗")
    }

    @SuppressLint("SetTextI18n")
    private fun onTargetLocationUpdated(wgsLoc: Pair<Double, Double>, animate: Boolean = false) {
        if (_binding == null) return
        aMapViewModel.markedLoc = wgsLoc
        updateMockButtonState()
        aMapViewModel.markName = null
        val gcj = wgsLoc.mapPoint
        binding.tvTargetCoords.text = formatCoordinates(wgsLoc)
        binding.tvTargetCoords.visibility = View.VISIBLE
        binding.tvTargetName.text = "已选位置"
        binding.tvTargetAddress.text = "正在获取地址…"
        selectedMarker?.remove()
        selectedMarker = binding.amapView.marker(gcj, "待确认位置", selected = true)
        if (animate) { hasCenteredInitialLocation = true; binding.amapView.move(gcj, animate = true) }
        addressJob?.cancel()
        val generation = ++addressGeneration
        val ui = binding
        addressJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val address = MapServices(requireContext(), ui.amapView.provider).address(gcj)
                if (_binding !== ui || generation != addressGeneration) return@launch
                aMapViewModel.markName = address.address
                ui.tvTargetName.text = address.title.ifBlank { "已选位置" }
                ui.tvTargetAddress.text = address.address.ifBlank { "地址暂不可用" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (_binding === ui && generation == addressGeneration) ui.tvTargetAddress.text = error.message ?: "地址暂不可用" }
        }
    }

    // The location editor expects longitude first, followed by latitude (WGS84).
    private fun formatCoordinates(wgs: Pair<Double, Double>): String =
        String.format(Locale.US, "%.6f, %.6f", wgs.second, wgs.first)

    private fun previewRoute(points: List<Pair<Double, Double>>) {
        routePreview?.remove()
        if (points.size >= 2) routePreview = binding.amapView.line(points.map { it.mapPoint }, Color.argb(200, 59, 130, 246), 12f)
    }

    @SuppressLint("SetTextI18n")
    private fun showAddLocationDialog() {
        val target = aMapViewModel.markedLoc ?: aMapViewModel.currentLocation
        if (target == null) {
            Toast.makeText(requireContext(), "未选定坐标点", Toast.LENGTH_SHORT).show()
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_location, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etLocationName)
        val editAddress = dialogView.findViewById<TextInputEditText>(R.id.etLocationAddress)
        val editLatLon = dialogView.findViewById<TextInputEditText>(R.id.etLocationLatLon)

        val defaultName = aMapViewModel.markName ?: "收藏地点-"
        editName.setText(defaultName)
        editAddress.setText(aMapViewModel.markName ?: "自定义地点")
        editLatLon.setText("${target.second}, ${target.first}")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("保存至位置库")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = editName.text?.toString()?.trim() ?: ""
                val address = editAddress.text?.toString()?.trim() ?: ""
                val latLonParts = editLatLon.text?.toString()?.split(",") ?: emptyList()
                val lon = latLonParts.getOrNull(0)?.trim()?.toDoubleOrNull()
                val lat = latLonParts.getOrNull(1)?.trim()?.toDoubleOrNull()

                if (name.isEmpty() || address.isEmpty() || lat == null || lon == null ||
                    lat !in -90.0..90.0 || lon !in -180.0..180.0 || latLonParts.size != 2) {
                    Toast.makeText(requireContext(), "填写内容不规范", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val saved = mockServiceViewModel.saveLocation(name, address, lat, lon)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = saved.await()
                    Toast.makeText(requireContext(), if (result.isSuccess) "已加入位置库" else "保存失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showInputCoordinatesDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_coordinates, null)
        val latitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val longitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLongitude)

        (aMapViewModel.markedLoc ?: aMapViewModel.currentLocation)?.let {
            latitudeEditText.setText(BigDecimal.valueOf(it.first).toPlainString())
            longitudeEditText.setText(BigDecimal.valueOf(it.second).toPlainString())
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("精确输入经纬度(WGS84)")
            .setView(dialogView)
            .setPositiveButton("跳转") { _, _ ->
                val lat = latitudeEditText.text?.toString()?.toDoubleOrNull()
                val lon = longitudeEditText.text?.toString()?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    val wgs = lat to lon
                    aMapViewModel.markedLoc = wgs
                    onTargetLocationUpdated(wgs, true)
                } else {
                    Toast.makeText(requireContext(), "请输入有效的经纬度数值", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onStart() { super.onStart(); _binding?.amapView?.onStart() }
    override fun onStop() { _binding?.amapView?.onStop(); super.onStop() }
    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            binding.amapView.onResume()
            displayingSimulation = mockServiceViewModel.runtimeState.value.isActive
            realLifecycle.resume(displayingSimulation)
            updateMockButtonState()
        }
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            realLifecycle.pause()
            binding.amapView.onPause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            binding.amapView.onSaveInstanceState(outState)
        }
    }

    override fun onDestroyView() {
        searchCoordinator?.destroy()
        searchCoordinator = null
        selectedMarker = null
        originalMarker = null
        routePreview = null
        isApplyingLocation = false
        aMapViewModel.isExists = false
        ++addressGeneration
        addressJob?.cancel(); addressJob = null
        realLifecycle.destroy()
        systemListener = null; systemLocation = null
        if (::mLocationClient.isInitialized) {
            mLocationClient.stopLocation()
            mLocationClient.onDestroy()
        }
        binding.amapView.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
