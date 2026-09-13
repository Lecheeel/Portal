package com.system.location.service.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.system.location.service.databinding.FragmentRouteEditBinding
import com.system.location.service.ext.gcj02
import com.system.location.service.ext.jsonHistoricalRoutes
import com.system.location.service.ext.mapType
import com.system.location.service.ext.Loc4j
import com.system.location.service.ext.wgs84
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.HomeViewModel
import kotlin.random.Random


class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private val routeEditViewModel by viewModels<HomeViewModel>()
    private lateinit var mLocationClient: AMapLocationClient
    private val aMapViewModel by activityViewModels<AMapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()
    private var isDrawing = false
    private var lastPoint: Pair<Double, Double>? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)

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
                if (Random.nextBoolean()) com.system.location.service.R.drawable.icon_my_location else null
            )

            setOnMapClickListener { loc ->
                // 高德返回gcj02坐标，需要转换一下
                aMapViewModel.markedLoc = loc.wgs84

                lifecycleScope.launch {
                    aMapViewModel.showDetailView = false
                    aMapViewModel.mGeoCoder?.getFromLocationAsyn(
                        RegeocodeQuery(LatLonPoint(loc.latitude, loc.longitude), 200f, GeocodeSearch.AMAP)
                    )
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
                    AMap.MAP_TYPE_NORMAL -> com.system.location.service.R.id.map_type_normal
                    AMap.MAP_TYPE_SATELLITE -> com.system.location.service.R.id.map_type_satellite
                    else -> com.system.location.service.R.id.map_type_normal
                }
            )
        }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabStart,
                binding.fabRollback,
                binding.fabComplete,
                binding.fabMyLocation
            )

            routeEditViewModel.mFabOpened = true

            if (!routeEditViewModel.mFabOpened) {
                routeEditViewModel.mFabOpened = true

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
                routeEditViewModel.mFabOpened = false

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

        mLocationClient = AMapLocationClient(requireContext())
        val option = AMapLocationClientOption()
        option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        option.setOnceLocation(false)
        option.setNeedAddress(true) /* 关掉这个无法获取当前城市 */
        option.setSensorEnable(true)
                option.setLocationCacheEnable(false)
        option.interval = 1000
        mLocationClient.setLocationOption(option)
        mLocationClient.setLocationListener { loc ->
            if (loc == null || loc.errorCode != 0) return@setLocationListener
            with(aMapViewModel) {
                currentLocation = loc.wgs84
            }
            if (!loc.city.isNullOrBlank())
                MainActivity.mCityString = loc.city
        }
        aMapViewModel.mLocationClient = mLocationClient
        mLocationClient.enableBackgroundLocation(1, aMapViewModel.mNotification)
        mLocationClient.startLocation()

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                com.system.location.service.R.id.map_type_normal -> {
                    binding.amapView.map.setMapType(AMap.MAP_TYPE_NORMAL)
                }

                com.system.location.service.R.id.map_type_satellite -> {
                    binding.amapView.map.setMapType(AMap.MAP_TYPE_SATELLITE)
                }

                else -> {
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                }
            }
            context?.mapType = binding.amapView.map.mapType
        }

        aMapViewModel.aMap.setOnMapTouchListener {
            if (isDrawing) {
                val target = aMapViewModel.aMap.projection.fromScreenLocation(Point(it.x.toInt(), it.y.toInt()))
                val currentPoint = Loc4j.gcj2wgs(target.latitude, target.longitude)

                when (it.action) {
                    MotionEvent.ACTION_DOWN -> { // 新增 DOWN 事件处理
                        if (mPoints.size <= 0) {
                            mPoints.add(currentPoint)
                        }
                        lastPoint = currentPoint
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (lastPoint == null) {
                            lastPoint = currentPoint
                        }
                        lastPoint?.let { lp -> drawLine(lp, currentPoint) }
                    }

                    MotionEvent.ACTION_UP -> {
                        mPoints.add(currentPoint)
                        lastPoint = null // 关键修改：重置起点
                    }
                }
            }
        }

        binding.fabStart.setOnClickListener {
            isDrawing = true;
            mPoints = arrayListOf()
            lastPoint = null; // 重置上一个点
        }

        binding.fabRollback.setOnClickListener {
            // 撤回上一个点并且刷新地图
            if (mPoints.size > 0) {
                mPoints.removeAt(mPoints.size - 1)
                refresh()
            }
        }

        binding.fabComplete.setOnClickListener {
            isDrawing = false
            if (!showAddRouteDialog()) {
                Toast.makeText(requireContext(), "选择路线异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabMyLocation.setOnClickListener {
            aMapViewModel.aMap.locateMe()
        }

        binding.amapView.onCreate(savedInstanceState)
        return binding.root
    }

    private fun refresh() {
        aMapViewModel.aMap.clear() // 清除之前的所有覆盖物

        // 绘制之前记录的点到点的线
        for (i in 0 until mPoints.size - 1) {
            aMapViewModel.aMap.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(mPoints[i].gcj02, mPoints[i + 1].gcj02)
            )
        }
    }

    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        aMapViewModel.aMap.clear() // 清除之前的所有覆盖物

        // 绘制之前记录的点到点的线
        for (i in 0 until mPoints.size - 1) {
            aMapViewModel.aMap.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(mPoints[i].gcj02, mPoints[i + 1].gcj02)
            )
        }

        aMapViewModel.aMap.addPolyline(
            PolylineOptions()
                .color(Color.argb(178, 0, 78, 255))
                .width(10f)
                .add(start.gcj02, end.gcj02)
        )
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

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddRouteDialog(): Boolean {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_route, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etRouteName)
        editName.addTextChangedListener {
            editName.error = if (it.isNullOrBlank()) "名称不能为空" else null
        }
        val editRoute = dialogView.findViewById<TextInputEditText>(R.id.etRouteSet)
        editRoute.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editRoute.error = "路线经纬度不能为空"
            } else {
                try {
                    RouteJson.decodePoints(it.toString())
                    editRoute.error = null
                } catch (e: IllegalArgumentException) {
                    editRoute.error = "路线格式错误，至少需要两个有效经纬度点"
                }
            }
        }

        editRoute.setText(RouteJson.encodePoints(mPoints))

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    editName.error = "名称不能为空"
                    return@setOnClickListener
                }
                val points = try {
                    RouteJson.decodePoints(editRoute.text.toString())
                } catch (e: IllegalArgumentException) {
                    editRoute.error = "路线格式错误，至少需要两个有效经纬度点"
                    return@setOnClickListener
                }
                val context = requireContext()
                val routes = try {
                    RouteJson.decodeRoutes(context.jsonHistoricalRoutes)
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(context, "历史路线数据格式错误，无法保存", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                context.jsonHistoricalRoutes = RouteJson.encodeRoutes(routes + HistoricalRoute(name, points))
                Toast.makeText(context, "路线已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()

        return true
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null)
            binding.amapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null)
            binding.amapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null)
            binding.amapView.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (_binding != null) {
            binding.amapView.map.isMyLocationEnabled = false
            if (mLocationClient.isStarted)
                mLocationClient.disableBackgroundLocation(true)
        }
        _binding = null
    }
}
