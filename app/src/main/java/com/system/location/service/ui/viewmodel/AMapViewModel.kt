package com.system.location.service.ui.viewmodel

import android.app.Notification
import androidx.lifecycle.ViewModel
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.geocoder.GeocodeSearch
import com.system.location.service.R

class AMapViewModel: ViewModel() {
    var isExists = false
    lateinit var aMap: AMap
    lateinit var mLocationClient: com.amap.api.location.AMapLocationClient

    /**
     * Current location
     * WGS84
     */
    var currentLocation: Pair<Double, Double>? = null

    var markName: String? = null

    /**
     * Marked location
     * WGS84
     * first => latitude
     * second => longitude
     */
    var markedLoc: Pair<Double, Double>? = null
    var showDetailView = false

    /* Notification */
    var mNotification: Notification? = null

    /**
     * 2024.10.10: Cancels the default follow perspective
     */
    var perspectiveState = AMapViewModel.Perspective.NORMAL
        set(value) {
            field = value
            applyPerspective(value)
        }

    enum class Perspective { NORMAL, FOLLOWING }

    private fun applyPerspective(mode: Perspective) {
        if (!::aMap.isInitialized) return
        val style = MyLocationStyle().apply {
            when (mode) {
                Perspective.NORMAL -> myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
                Perspective.FOLLOWING -> myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE)
            }
            showMyLocation(true)
        }
        aMap.myLocationStyle = style
        aMap.isMyLocationEnabled = true
    }

    val mMapIndicator: BitmapDescriptor? by lazy {
        BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
    }

    var mGeoCoder: GeocodeSearch? = null
}
