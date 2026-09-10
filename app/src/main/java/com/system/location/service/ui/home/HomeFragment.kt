package com.system.location.service.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.amap.locateMe
import com.system.location.service.amap.setMapConfig
import com.system.location.service.databinding.FragmentHomeBinding
import com.system.location.service.ext.gcj02
import com.system.location.service.ext.mapType
import com.system.location.service.ext.rawHistoricalLocations
import com.system.location.service.ext.selectRoute
import com.system.location.service.ext.wgs84
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.HomeViewModel
import java.math.BigDecimal
import kotlin.random.Random

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val homeViewModel by viewModels<HomeViewModel>()
    private lateinit var mLocationClient: AMapLocationClient
    private val aMapViewModel by activityViewModels<AMapViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Fixed the issue that the Fab was opening incorrectly after switching back to Home for Fragments
        homeViewModel.mFabOpened = false

        with(aMapViewModel) {
            isExists = true
            aMap = binding.amapView.map
        }

        with(binding.amapView.map) {
            moveCamera(CameraUpdateFactory.zoomTo(19f))

            setMapType(context?.mapType ?: AMap.MAP_TYPE_NORMAL)
            uiSettings.isCompassEnabled = true
            uiSettings.isTiltGesturesEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isScaleControlsEnabled = true
            isMyLocationEnabled = true

            setMapConfig(
                aMapViewModel.perspectiveState,
                if (Random.nextBoolean()) R.drawable.icon_my_location else null
            )

            setOnMapClickListener { loc ->
                // 高德返回gcj02坐标，需要转换一下
                aMapViewModel.markedLoc = loc.wgs84

                lifecycleScope.launch {
                    aMapViewModel.showDetailView = false
                    aMapViewModel.mGeoCoder?.let { geo ->
                        geo.getFromLocationAsyn(
                            RegeocodeQuery(LatLonPoint(loc.latitude, loc.longitude), 200f, GeocodeSearch.AMAP)
                        )
                    }
                }

                // Fixed the issue that getting geolocation information was stuck
                lifecycleScope.launch {
                    markMap()
                }
            }

            setOnMapLongClickListener { loc ->
                // 高德返回gcj02坐标，需要转换一下
                aMapViewModel.markedLoc = loc.wgs84
                lifecycleScope.launch {
                    aMapViewModel.showDetailView = true
                    aMapViewModel.mGeoCoder?.getFromLocationAsyn(
                        RegeocodeQuery(LatLonPoint(loc.latitude, loc.longitude), 200f, GeocodeSearch.AMAP)
                    )
                }
                lifecycleScope.launch {
                    markMap()
                }
            }

            binding.mapTypeGroup.check(
                when (mapType) {
                    AMap.MAP_TYPE_NORMAL -> R.id.map_type_normal
                    AMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
                    else -> R.id.map_type_normal
                }
            )
        }

        mLocationClient = AMapLocationClient(requireContext())
        val option = AMapLocationClientOption()
        option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        option.setOnceLocation(false)
        option.setNeedAddress(true) /* 关掉这个无法获取当前城市 */
        option.setSensorEnable(true)
                option.setGpsFirst(false)
        option.setLocationCacheEnable(false)
        option.interval = 1000
        mLocationClient.setLocationOption(option)
        mLocationClient.setLocationListener { loc ->
            if (loc == null || loc.errorCode != 0) return@setLocationListener
            val style = MyLocationStyle().apply {
                myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
                showMyLocation(true)
            }
            with(aMapViewModel) {
                currentLocation = loc.wgs84
                aMap.myLocationStyle = style
                aMap.isMyLocationEnabled = true
            }
            if (!loc.city.isNullOrBlank())
                MainActivity.mCityString = loc.city
        }
        aMapViewModel.mLocationClient = mLocationClient
        mLocationClient.enableBackgroundLocation(1, aMapViewModel.mNotification)
        mLocationClient.startLocation()


        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.map_type_normal -> {
                    binding.amapView.map.setMapType(AMap.MAP_TYPE_NORMAL)
                }

                R.id.map_type_satellite -> {
                    binding.amapView.map.setMapType(AMap.MAP_TYPE_SATELLITE)
                }

                else -> {
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                }
            }
            context?.mapType = binding.amapView.map.mapType
        }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabMyLocation,
                binding.fabGoto,
                binding.fabAdd
            )

            if (!homeViewModel.mFabOpened) {
                homeViewModel.mFabOpened = true

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 0f, 90f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    fab.visibility = View.VISIBLE
                    fab.alpha = 1f
                    fab.scaleX = 1f
                    fab.scaleY = 1f
                    val translationX =
                        ObjectAnimator.ofFloat(fab, "translationX", 0f, 20f + index * 8f)
                    translationX.duration = 200
                    animators.add(translationX)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            } else {
                homeViewModel.mFabOpened = false

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 90f, 0f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    val transX = ObjectAnimator.ofFloat(fab, "translationX", 0f, -20f - index * 8f)
                    transX.duration = 150
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 0f)
                    scaleX.duration = 200
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 0f)
                    scaleY.duration = 200
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", 1f, 0f)
                    alpha.duration = 200
                    animators.add(transX)
                    animators.add(scaleX)
                    animators.add(scaleY)
                    animators.add(alpha)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        subFabList.forEach { it.visibility = View.GONE }
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            }
        }

        binding.fabMyLocation.setOnClickListener {
            aMapViewModel.aMap.locateMe()
        }

        binding.fabGoto.setOnClickListener {
            showInputCoordinatesDialog()
        }

        binding.fabAdd.setOnClickListener {
            if (!showAddLocationDialog()) {
                Toast.makeText(requireContext(), "选择位置异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.showRoute.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requireContext().selectRoute?.route?.let {
                    previewRoute(it)
                    // 选中路线后，将视角移动到起点
                    aMapViewModel.aMap.moveCamera(
                        CameraUpdateFactory.changeLatLng(it.first().gcj02)
                    )
                }
            } else {
                aMapViewModel.aMap.clear()
            }
        }

        return root
    }

    private fun previewRoute(points: kotlin.collections.List<Pair<Double, Double>>) {
        aMapViewModel.aMap.clear() // 清除之前的所有覆盖物

        // 绘制之前记录的点到点的线
        for (i in 0 until points.size - 1) {
            aMapViewModel.aMap.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(points[i].gcj02, points[i + 1].gcj02)
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.amapView.onCreate(savedInstanceState)
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddLocationDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_location, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etLocationName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editAddress = dialogView.findViewById<TextInputEditText>(R.id.etLocationAddress)
        editAddress.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editAddress.error = "地址不能为空"
            }
        }
        val editLatLon = dialogView.findViewById<TextInputEditText>(R.id.etLocationLatLon)
        editLatLon.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editLatLon.error = "经纬度不能为空"
            } else {
                val latLonArray = it.toString().split(",")
                if (latLonArray.size != 2) {
                    editLatLon.error = "经纬度格式错误"
                } else {
                    val lon = latLonArray[0].trim().toDoubleOrNull()
                    val lat = latLonArray[1].trim().toDoubleOrNull()
                    if (!checkLatLon(lat, lon)) {
                        editLatLon.error = "经纬度格式错误"
                    }
                }
            }
        }

        with(aMapViewModel) {
            if (markedLoc == null) {
                currentLocation?.let {
                    showDetailView = false
                    mGeoCoder?.getFromLocationAsyn(
                        RegeocodeQuery(LatLonPoint(it.gcj02.latitude, it.gcj02.longitude), 200f, GeocodeSearch.AMAP)
                    )
                    markedLoc = it
                }
                editName.setText("当前位置-" + System.currentTimeMillis())
            } else {
                editName.setText("标点位置-" + System.currentTimeMillis())
            }

            val lat = BigDecimal.valueOf(markedLoc?.first ?: return false)
            val lon = BigDecimal.valueOf(markedLoc?.second ?: return false)

            editAddress.setText(markName ?: "位置地址")
            editLatLon.setText("${lon.toPlainString()}, ${lat.toPlainString()}")

            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle(null)
            builder
                .setCancelable(false)
                .setView(dialogView)
                .setPositiveButton("保存") { _, _ ->
                    val latLonArray = editLatLon.text.toString().split(",")
                    val newLon = latLonArray[0].trim().toDoubleOrNull()
                    val newLat = latLonArray[1].trim().toDoubleOrNull()
                    var name = editName.text?.toString()
                    val address = editAddress.text?.toString()
                    if (name.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (address.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "地址不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!checkLatLon(newLat, newLon)) {
                        Toast.makeText(requireContext(), "经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }

                    fun MutableSet<String>.addLocation(
                        name: String,
                        address: String,
                        lat: Double,
                        lon: Double
                    ): Boolean {
                        if (any { it.split(",")[0] == name }) {
                            return false
                        }
                        add(
                            "$name,$address,${
                                BigDecimal.valueOf(lat).toPlainString()
                            },${BigDecimal.valueOf(lon).toPlainString()}"
                        )
                        return true
                    }

                    with(requireContext()) {
                        val locations = rawHistoricalLocations.toMutableSet()
                        var count = 0
                        while (!locations.addLocation(name!!, address, newLat!!, newLon!!)) {
                            name = "$name(${++count})"
                        }
                        rawHistoricalLocations = locations
                    }

                    Toast.makeText(requireContext(), "位置已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        return true
    }

    @SuppressLint("MissingInflatedId")
    private fun showInputCoordinatesDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_coordinates, null)

        val latitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val longitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLongitude)

        aMapViewModel.currentLocation?.let {
            latitudeEditText.setText(BigDecimal.valueOf(it.first).toPlainString())
            longitudeEditText.setText(BigDecimal.valueOf(it.second).toPlainString())
        }

        latitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                latitudeEditText.error = "纬度不能为空"
            } else {
                val lat = it.toString().toDoubleOrNull()
                if (lat == null || lat !in -90.0..90.0) {
                    latitudeEditText.error = "纬度格式错误"
                }
            }
        }

        longitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                longitudeEditText.error = "经度不能为空"
            } else {
                val lon = it.toString().toDoubleOrNull()
                if (lon == null || lon !in -180.0..180.0) {
                    longitudeEditText.error = "经度格式错误"
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("输入经纬度(WGS84)")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                kotlin.runCatching {
                    val latitude = latitudeEditText.text.toString()
                    val longitude = longitudeEditText.text.toString()

                    if (latitude.isNotEmpty() && longitude.isNotEmpty()) with(aMapViewModel) {
                        val lat = latitude.toDoubleOrNull()
                        val lon = longitude.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            throw IllegalArgumentException("Invalid latitude or longitude")
                        }

                        this.markedLoc = lat to lon

                        markMap(true)

                        if (perspectiveState == AMapViewModel.Perspective.FOLLOWING) {
                            perspectiveState = AMapViewModel.Perspective.NORMAL
                        }

                        mGeoCoder?.getFromLocationAsyn(
                            RegeocodeQuery(LatLonPoint(lat, lon), 200f, GeocodeSearch.AMAP)
                        )
                    } else {
                        Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                            .show()
                    }
                }.onFailure {
                    Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun markMap(moveEyes: Boolean = false) = with(aMapViewModel) {
        val loc = markedLoc!!.gcj02
        val ooA = MarkerOptions()
            .position(loc)
            .icon(mMapIndicator)
        aMap.clear()
        aMap.addMarker(ooA)

        if (moveEyes) {
            aMap.moveCamera(CameraUpdateFactory.changeLatLng(loc))
        }
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null)
            binding.amapView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()

        aMapViewModel.isExists = false
        if (mLocationClient.isStarted)
            mLocationClient.stopLocation()
        if (_binding != null) {
            binding.amapView.map.isMyLocationEnabled = false
            mLocationClient.onDestroy()
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
        super.onDestroyView()
        _binding = null
    }
}
