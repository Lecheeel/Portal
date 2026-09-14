package com.system.location.service.ui.home

import android.annotation.SuppressLint
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.LocationSource
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.amap.locateMe
import com.system.location.service.amap.setMapConfig
import com.system.location.service.amap.toPoi
import com.system.location.service.android.widget.RockerView
import com.system.location.service.android.window.OverlayUtils
import com.system.location.service.bdmap.Poi
import com.system.location.service.databinding.FragmentHomeBinding
import com.system.location.service.ext.altitude
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.gcj02
import com.system.location.service.ext.lastKnownLat
import com.system.location.service.ext.lastKnownLng
import com.system.location.service.ext.mapType
import com.system.location.service.ext.rawHistoricalLocations
import com.system.location.service.ext.selectLocation
import com.system.location.service.ext.selectRoute
import com.system.location.service.ext.speed
import com.system.location.service.ext.wgs84
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.HomeViewModel
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import java.math.BigDecimal
import java.util.Locale

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel by viewModels<HomeViewModel>()
    private val aMapViewModel by activityViewModels<AMapViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    private lateinit var mLocationClient: AMapLocationClient
    private var mInputtips: Inputtips? = null
    private var hasCenteredInitialLocation = false
    private var isDraggingMap = false
    private var targetGeocoder: GeocodeSearch? = null
    private var blueDotListener: LocationSource.OnLocationChangedListener? = null
    private var displayingSimulation = false
    private var realPositionFilter = PositionFilter()
    private var targetUpdates = Channel<Pair<Double, Double>>(Channel.CONFLATED)

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        targetUpdates = Channel(Channel.CONFLATED)
        realPositionFilter = PositionFilter()
        binding.amapView.onCreate(savedInstanceState)
        val root: View = binding.root

        with(aMapViewModel) {
            isExists = true
            aMap = binding.amapView.map
        }

        setupMap()
        setupLocationClient()
        setupSearchBar()
        setupQuickActions()
        setupBottomPanel()
        aMapViewModel.markedLoc?.let { onTargetLocationUpdated(it, true, false) }

        return root
    }

    private fun setupMap() {
        with(binding.amapView.map) {
            setMapType(context?.mapType ?: AMap.MAP_TYPE_NORMAL)
            uiSettings.isCompassEnabled = true
            uiSettings.isTiltGesturesEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isScaleControlsEnabled = true
            setLocationSource(object : LocationSource {
                override fun activate(listener: LocationSource.OnLocationChangedListener?) { blueDotListener = listener }
                override fun deactivate() { blueDotListener = null }
            })

            // Cold start restore last known location immediately
            val savedLat = requireContext().lastKnownLat
            val savedLng = requireContext().lastKnownLng
            if (savedLat != 0.0 && savedLng != 0.0) {
                val lastGcj = (savedLat to savedLng).gcj02
                moveCamera(CameraUpdateFactory.newLatLngZoom(lastGcj, 16f))
                hasCenteredInitialLocation = true
            } else {
                moveCamera(CameraUpdateFactory.zoomTo(16f))
            }

            setMapConfig(aMapViewModel.perspectiveState, null)

            setOnMapClickListener { loc ->
                onTargetLocationUpdated(loc.wgs84, true)
            }

            setOnMapTouchListener { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> isDraggingMap = false
                    MotionEvent.ACTION_MOVE -> isDraggingMap = true
                }
            }
            setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                override fun onCameraChange(position: com.amap.api.maps.model.CameraPosition) {
                    if (_binding == null || !isDraggingMap) return
                    val wgs = position.target.wgs84
                    aMapViewModel.markedLoc = wgs
                    aMapViewModel.markName = null
                    binding.tvTargetCoords.text = formatCoordinates(wgs)
                    binding.tvTargetName.text = "已选位置"
                    binding.tvTargetAddress.text = "松开地图获取地址"
                }
                override fun onCameraChangeFinish(position: com.amap.api.maps.model.CameraPosition) {
                    if (_binding == null) return
                    val wgs = position.target.wgs84
                    requireContext().lastKnownLat = wgs.first
                    requireContext().lastKnownLng = wgs.second
                    if (isDraggingMap || aMapViewModel.markedLoc == null) {
                        val userSelection = isDraggingMap
                        if (isDraggingMap) hasCenteredInitialLocation = true
                        isDraggingMap = false
                        onTargetLocationUpdated(wgs, applyToMock = userSelection)
                    }
                }
            })
        }
    }

    private fun setupLocationClient() {
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
            if (_binding == null || displayingSimulation || loc == null || loc.errorCode != 0) return@setLocationListener

            val wgs = realPositionFilter.accept(loc.wgs84, loc.time, loc.accuracy, loc.speed) ?: return@setLocationListener
            showCurrentPosition(wgs, loc.accuracy)
            requireContext().lastKnownLat = wgs.first
            requireContext().lastKnownLng = wgs.second

            if (!loc.city.isNullOrBlank()) {
                MainActivity.mCityString = loc.city
            }

            // Fly smoothly to user's real location on first fix if not already positioned
            if (!hasCenteredInitialLocation) {
                hasCenteredInitialLocation = true
                val gcj = LatLng(loc.latitude, loc.longitude)
                binding.amapView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(gcj, 16.5f), 700, null)
            }
        }
        aMapViewModel.mLocationClient = mLocationClient
        mLocationClient.enableBackgroundLocation(1, aMapViewModel.mNotification)
        // The resumed-state collector checks simulation before starting real positioning.
    }

    private fun showCurrentPosition(wgs: Pair<Double, Double>, accuracy: Float) {
        aMapViewModel.currentLocation = wgs
        val gcj = wgs.gcj02
        blueDotListener?.onLocationChanged(Location("gps").apply {
            latitude = gcj.latitude
            longitude = gcj.longitude
            this.accuracy = accuracy.coerceAtLeast(0.1f)
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            for (target in targetUpdates) {
                val applied = if (mockServiceViewModel.isServiceStart())
                    mockServiceViewModel.startPoint(target) else null
                if (applied == true) {
                    displayingSimulation = true
                    showCurrentPosition(target, requireContext().accuracy)
                } else if (applied == false) {
                    Toast.makeText(requireContext(), "目标位置同步失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                mockServiceViewModel.runtimeState.collect { state ->
                    val running = state.isActive
                    val position = state.sample?.coordinate?.let { it.latitude to it.longitude }
                    updateMockButtonState()
                    displayingSimulation = running
                    if (running) {
                        if (mLocationClient.isStarted) mLocationClient.stopLocation()
                        position?.let { showCurrentPosition(it, requireContext().accuracy) }
                    } else if (!mLocationClient.isStarted) {
                        realPositionFilter = PositionFilter()
                        mLocationClient.startLocation()
                    }
                }
            }
        }
    }

    private fun setupSearchBar() {
        // Menu button opens the drawer in MainActivity
        binding.btnMenu.setOnClickListener {
            val mainActivity = activity as? MainActivity
            val drawer = mainActivity?.binding?.drawerLayout
            drawer?.openDrawer(GravityCompat.START)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchQuery.setText("")
            binding.cardSearchResults.visibility = View.GONE
        }

        binding.etSearchQuery.addTextChangedListener { text ->
            val query = text?.toString()?.trim() ?: ""
            binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            if (query.isNotEmpty()) {
                performInputTipsSearch(query)
            } else {
                binding.cardSearchResults.visibility = View.GONE
            }
        }

        binding.btnDoSearch.setOnClickListener {
            val query = binding.etSearchQuery.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                performInputTipsSearch(query)
            }
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
                binding.etSearchQuery.setText(nameText ?: "")
            }
        }
    }

    private fun performInputTipsSearch(query: String) {
        if (mInputtips == null) {
            mInputtips = Inputtips(requireContext(), object : Inputtips.InputtipsListener {
                override fun onGetInputtips(tips: MutableList<Tip>?, code: Int) {
                    if (_binding == null) return
                    if (code != 1000 || tips.isNullOrEmpty()) {
                        binding.cardSearchResults.visibility = View.GONE
                        return
                    }
                    val data = tips.filter { it.point != null }
                        .toPoi(aMapViewModel.currentLocation)
                        .map { it.toMap() }

                    val adapter = SimpleAdapter(
                        requireContext(),
                        data,
                        R.layout.layout_search_poi_item,
                        arrayOf(Poi.KEY_NAME, Poi.KEY_ADDRESS, Poi.KEY_LONGITUDE_RAW, Poi.KEY_LATITUDE_RAW, Poi.KEY_TAG),
                        intArrayOf(R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude, R.id.poi_tag)
                    )
                    binding.lvSearchResults.adapter = adapter
                    binding.cardSearchResults.visibility = View.VISIBLE
                }
            })
        }
        try {
            mInputtips?.setQuery(InputtipsQuery(query, MainActivity.mCityString ?: ""))
            mInputtips?.requestInputtipsAsyn()
        } catch (e: Exception) {
            Log.e("HomeFragment", "Search error: ")
        }
    }

    private fun setupQuickActions() {
        // Toggle Normal / Satellite View
        binding.fabMapType.setOnClickListener {
            val current = binding.amapView.map.mapType
            val next = if (current == AMap.MAP_TYPE_NORMAL) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
            binding.amapView.map.setMapType(next)
            requireContext().mapType = next
            Toast.makeText(
                requireContext(),
                if (next == AMap.MAP_TYPE_SATELLITE) "已切换为卫星图" else "已切换为标准图",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Toggle Route Preview
        var isRouteShown = false
        binding.fabToggleRoute.setOnClickListener {
            isRouteShown = !isRouteShown
            if (isRouteShown) {
                val route = requireContext().selectRoute?.route
                if (!route.isNullOrEmpty()) {
                    previewRoute(route)
                    binding.amapView.map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(route.first().gcj02, 16f)
                    )
                    binding.fabToggleRoute.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.primary)
                    )
                    Toast.makeText(requireContext(), "已加载选中路线", Toast.LENGTH_SHORT).show()
                } else {
                    isRouteShown = false
                    Toast.makeText(requireContext(), "未选择路线，请先在路线库中选取", Toast.LENGTH_SHORT).show()
                }
            } else {
                aMapViewModel.aMap.clear()
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
                onTargetLocationUpdated(cur, true)
            } else {
                binding.amapView.map.locateMe()
            }
        }
    }

    private fun setupBottomPanel() {
        updateMockButtonState()

        // Quick Mock start/stop
        binding.btnQuickMock.setOnClickListener {
            val isRunning = mockServiceViewModel.isServiceStart()
            if (isRunning) {
                tryCloseMock()
            } else {
                tryOpenMock()
            }
        }

        // Toggle Floating Rocker
        binding.btnQuickRocker.setOnClickListener {
            if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
                Toast.makeText(requireContext(), "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (mockServiceViewModel.locationManager == null) {
                Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!mockServiceViewModel.isServiceStart()) {
                Toast.makeText(requireContext(), "请先启动模拟后再使用摇杆", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val rocker = mockServiceViewModel.rocker
            if (rocker.isStart) {
                rocker.hide()
                mockServiceViewModel.setMoving(false)
            } else {
                rocker.show()
                rocker.setRockerListener(object : RockerView.Companion.OnMoveListener {
                    override fun onAngle(angle: Double) {
                        mockServiceViewModel.setBearing(angle)
                    }

                    override fun onLockChanged(isLocked: Boolean) {
                        mockServiceViewModel.isRockerLocked = isLocked
                    }

                    override fun onFinished() {
                        if (!mockServiceViewModel.isRockerLocked) {
                            mockServiceViewModel.setMoving(false)
                        }
                    }

                    override fun onStarted() {
                        mockServiceViewModel.setMoving(true)
                    }
                })
            }
        }

        // Save location
        binding.btnSaveLocation.setOnClickListener {
            showAddLocationDialog()
        }
    }

    private fun tryOpenMock() {
        val target = aMapViewModel.markedLoc ?: aMapViewModel.currentLocation
        if (target == null) {
            Toast.makeText(requireContext(), "请先在地图上选择位置", Toast.LENGTH_SHORT).show()
            return
        }
        val name = aMapViewModel.markName ?: "目标位置"
        val hist = HistoricalLocation(name, name, target.first, target.second)
        requireContext().selectLocation = hist
        mockServiceViewModel.selectedLocation = hist

        val speed = requireContext().speed
        val altitude = requireContext().altitude
        val accuracy = requireContext().accuracy

        lifecycleScope.launch {
            val started = mockServiceViewModel.startPoint(target, name)
            if (_binding == null) return@launch
            displayingSimulation = started
            if (started) showCurrentPosition(target, accuracy)
            updateMockButtonState()
            Toast.makeText(requireContext(), if (started) "已开始位置模拟" else mockServiceViewModel.failureMessage(), Toast.LENGTH_LONG).show()
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
            if (mockServiceViewModel.rocker.isStart) {
                mockServiceViewModel.rocker.hide()
                mockServiceViewModel.setMoving(false)
            }
            updateMockButtonState()
            Toast.makeText(requireContext(), "模拟已停止", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMockButtonState() {
        if (_binding == null) return
        val isRunning = mockServiceViewModel.isServiceStart()
        if (isRunning) {
            binding.btnQuickMock.text = "停止模拟"
            binding.btnQuickMock.setIconResource(R.drawable.baseline_stop_24)
            binding.btnQuickMock.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.red500)
            )
        } else {
            binding.btnQuickMock.text = getString(R.string.start_mock)
            binding.btnQuickMock.setIconResource(R.drawable.baseline_play_24)
            binding.btnQuickMock.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun onTargetLocationUpdated(wgsLoc: Pair<Double, Double>, animate: Boolean = false, applyToMock: Boolean = true) {
        if (_binding == null) return
        aMapViewModel.markedLoc = wgsLoc
        if (applyToMock) {
            mockServiceViewModel.pauseMovement()
            targetUpdates.trySend(wgsLoc)
        }
        aMapViewModel.markName = null
        val gcj = wgsLoc.gcj02
        binding.tvTargetCoords.text = formatCoordinates(wgsLoc)
        binding.tvTargetName.text = "已选位置"
        binding.tvTargetAddress.text = "正在获取地址信息..."

        // Place marker on map
        aMapViewModel.aMap.clear()
        val marker = MarkerOptions()
            .position(gcj)
            .icon(aMapViewModel.mMapIndicator)
        aMapViewModel.aMap.addMarker(marker)

        if (animate) {
            hasCenteredInitialLocation = true
            isDraggingMap = false
            binding.amapView.map.animateCamera(CameraUpdateFactory.changeLatLng(gcj), 400, null)
        }

        // Reverse Geocode
        try {
            if (targetGeocoder == null) {
                targetGeocoder = GeocodeSearch(requireContext())
                targetGeocoder?.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                        if (_binding == null) return
                        if (result == null) {
                            binding.tvTargetAddress.text = "地址暂不可用，坐标已选定"
                            return
                        }
                        val current = aMapViewModel.markedLoc?.gcj02 ?: return
                        val queried = result.regeocodeQuery.point
                        if (kotlin.math.abs(current.latitude - queried.latitude) > 0.000001 ||
                            kotlin.math.abs(current.longitude - queried.longitude) > 0.000001) return
                        if (code != 1000) {
                            binding.tvTargetAddress.text = "地址暂不可用，坐标已选定"
                            return
                        }
                        val addr = result.regeocodeAddress.formatAddress
                        aMapViewModel.markName = addr
                        binding.tvTargetName.text = if (result.regeocodeAddress.pois.isNotEmpty()) {
                            result.regeocodeAddress.pois.first().title
                        } else {
                            result.regeocodeAddress.district ?: "已选位置"
                        }
                        binding.tvTargetAddress.text = addr.ifBlank { "地址暂不可用，坐标已选定" }
                    }

                    override fun onGeocodeSearched(result: com.amap.api.services.geocoder.GeocodeResult?, code: Int) {}
                })
            }
            targetGeocoder?.getFromLocationAsyn(
                RegeocodeQuery(LatLonPoint(gcj.latitude, gcj.longitude), 200f, GeocodeSearch.AMAP)
            )
        } catch (e: Exception) {
            binding.tvTargetAddress.text = "地址暂不可用，坐标已选定"
            Log.w("HomeFragment", "Reverse geocoding failed", e)
        }
    }

    // The location editor expects longitude first, followed by latitude (WGS84).
    private fun formatCoordinates(wgs: Pair<Double, Double>): String =
        String.format(Locale.US, "%.6f, %.6f", wgs.second, wgs.first)

    private fun previewRoute(points: List<Pair<Double, Double>>) {
        aMapViewModel.aMap.clear()
        if (points.size >= 2) aMapViewModel.aMap.addPolyline(
            PolylineOptions().color(Color.argb(200, 59, 130, 246)).width(12f).addAll(points.map { it.gcj02 })
        )
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

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            binding.amapView.onResume()
            updateMockButtonState()
        }
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
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
        targetUpdates.close()
        aMapViewModel.isExists = false
        targetGeocoder?.setOnGeocodeSearchListener(null)
        targetGeocoder = null
        if (::mLocationClient.isInitialized) {
            mLocationClient.stopLocation()
            mLocationClient.onDestroy()
        }
        binding.amapView.map.isMyLocationEnabled = false
        binding.amapView.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
