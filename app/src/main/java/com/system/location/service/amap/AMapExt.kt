package com.system.location.service.amap

import android.graphics.Color
import android.util.Log
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.help.Tip
import com.system.location.service.bdmap.Poi
import com.system.location.service.ext.Loc4j
import com.system.location.service.ui.viewmodel.AMapViewModel

/**
 * 高德输入提示(Tip) -> Poi（WGS84）
 */
fun List<Tip>.toPoi(
    currentLocation: Pair<Double, Double>? = null
): List<Poi> = this
    .filter { !it.name.isNullOrBlank() && it.point != null }
    .map {
        val gcj02Lat = it.point.latitude
        val gcj02Lon = it.point.longitude
        val (lat, lon) = Loc4j.gcj2wgs(gcj02Lat, gcj02Lon)
        val address = listOfNotNull(it.district, it.name)
            .filter { s -> s.isNotBlank() }
            .joinToString(" ")
        if (currentLocation != null) {
            Log.d("toPoi", "currentLocation: $currentLocation, lat: $lat, lon: $lon")
            Poi(
                name = it.name,
                address = address,
                longitude = lon,
                latitude = lat,
                tag = "",
            ).also {
                val distance = it.distanceTo(currentLocation.first, currentLocation.second).toInt()
                if (distance < 1000) {
                    it.address = "${distance}m ${it.address}"
                } else {
                    it.address = "${(distance / 1000.0).toString().take(4)}km ${it.address}"
                }
            }
        } else {
            Poi(
                name = it.name,
                address = address,
                longitude = lon,
                latitude = lat,
                tag = "",
            )
        }
    }

fun AMap.setMapConfig(mode: AMapViewModel.Perspective, resourceId: Int?) {
    val style = MyLocationStyle()
    if (resourceId != null) {
        style.myLocationIcon(BitmapDescriptorFactory.fromResource(resourceId))
        style.strokeColor(Color.TRANSPARENT)
        style.radiusFillColor(Color.TRANSPARENT)
    }
    style.myLocationType(
        when (mode) {
            AMapViewModel.Perspective.NORMAL ->
                MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER
            AMapViewModel.Perspective.FOLLOWING ->
                MyLocationStyle.LOCATION_TYPE_MAP_ROTATE
        }
    )
    myLocationStyle = style
    isMyLocationEnabled = true
}

fun AMap.locateMe() {
    // 高德没有"跟随一次"的直接等价：先切 FOLLOWING 再切回 NORMAL 触发一次居中
    myLocationStyle = MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE)
    isMyLocationEnabled = true
    myLocationStyle = MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
}
