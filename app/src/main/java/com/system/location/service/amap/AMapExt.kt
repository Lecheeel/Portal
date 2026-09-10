package com.system.location.service.amap

import android.graphics.Color
import android.util.Log
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
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
            Log.d("toPoi", "currentLocation: , lat: , lon: ")
            Poi(
                name = it.name,
                address = address,
                longitude = lon,
                latitude = lat,
                tag = "",
            ).also {
                val distance = it.distanceTo(currentLocation.first, currentLocation.second).toInt()
                if (distance < 1000) {
                    it.address = "m "
                } else {
                    it.address = "km "
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

fun AMap.locateMe(target: LatLng? = null) {
    if (target != null && target.latitude != 0.0 && target.longitude != 0.0) {
        animateCamera(CameraUpdateFactory.newLatLngZoom(target, 17f), 600, null)
    } else {
        val myLoc = myLocation
        if (myLoc != null && myLoc.latitude != 0.0 && myLoc.longitude != 0.0) {
            animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLoc.latitude, myLoc.longitude), 17f), 600, null)
        } else {
            myLocationStyle = MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
            isMyLocationEnabled = true
        }
    }
}
