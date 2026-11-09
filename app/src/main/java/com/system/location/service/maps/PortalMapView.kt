package com.system.location.service.maps

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.planning.MapCamera
import com.system.location.service.privacy.TencentPrivacy
import com.amap.api.maps.CameraUpdateFactory as ACamera
import com.amap.api.maps.model.LatLng as APoint
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory as TCamera
import com.tencent.tencentmap.mapsdk.maps.model.LatLng as TPoint

interface MapOverlay { fun remove() }
interface MapLine : MapOverlay { fun points(points: List<Gcj02>) }

/** Only the selected SDK view is constructed, after its consent gate. Coordinates are GCJ-02. */
class PortalMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    val provider = context.mapProvider
    private var amap: com.amap.api.maps.MapView? = null
    private var tencent: com.tencent.tencentmap.mapsdk.maps.MapView? = null
    private var current: MapOverlay? = null
    private var destroyed = false
    fun onCreate(state: Bundle?) {
        check(!destroyed && childCount == 0)
        if (provider == MapProvider.AMAP) {
            check(com.system.location.service.privacy.SdkPrivacy.mapsReady)
            amap = com.amap.api.maps.MapView(context).also { view ->
                addView(view, LayoutParams(-1, -1)); view.onCreate(state)
                view.map.uiSettings.apply {
                    isCompassEnabled = true; isZoomControlsEnabled = false
                    isScaleControlsEnabled = true; isMyLocationButtonEnabled = false
                }
            }
        } else {
            check(TencentPrivacy.ready(context)) { "请先同意腾讯地图隐私政策" }
            tencent = com.tencent.tencentmap.mapsdk.maps.MapView(context).also { view ->
                addView(view, LayoutParams(-1, -1))
                view.map.uiSettings.apply {
                    isCompassEnabled = true; isZoomControlsEnabled = false
                    isScaleViewEnabled = true; isMyLocationButtonEnabled = false
                }
            }
        }
        satellite(context.satelliteMap)
    }
    fun satellite(enabled: Boolean) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        amap?.map?.mapType = if (enabled) 2 else if (dark) 3 else 1
        tencent?.map?.mapType = if (enabled) com.tencent.tencentmap.mapsdk.maps.TencentMap.MAP_TYPE_SATELLITE
            else if (dark) com.tencent.tencentmap.mapsdk.maps.TencentMap.MAP_TYPE_DARK else com.tencent.tencentmap.mapsdk.maps.TencentMap.MAP_TYPE_NORMAL
    }
    fun move(point: Gcj02, zoom: Float? = null, animate: Boolean = false) {
        amap?.map?.let { map ->
            val update = if (zoom == null) ACamera.changeLatLng(APoint(point.latitude, point.longitude))
                else ACamera.newLatLngZoom(APoint(point.latitude, point.longitude), zoom)
            if (animate) map.animateCamera(update) else map.moveCamera(update)
        }
        tencent?.map?.let { map ->
            val update = if (zoom == null) TCamera.newLatLng(TPoint(point.latitude, point.longitude))
                else TCamera.newLatLngZoom(TPoint(point.latitude, point.longitude), zoom)
            if (animate) map.animateCamera(update) else map.moveCamera(update)
        }
    }
    fun camera(camera: MapCamera) {
        amap?.map?.moveCamera(ACamera.newCameraPosition(com.amap.api.maps.model.CameraPosition(
            APoint(camera.target.latitude, camera.target.longitude), camera.zoom, camera.tilt, camera.bearing)))
        tencent?.map?.moveCamera(TCamera.newCameraPosition(com.tencent.tencentmap.mapsdk.maps.model.CameraPosition(
            TPoint(camera.target.latitude, camera.target.longitude), camera.zoom, camera.tilt, camera.bearing)))
    }
    fun onClick(callback: ((Gcj02) -> Unit)?) {
        amap?.map?.setOnMapClickListener(callback?.let { cb -> com.amap.api.maps.AMap.OnMapClickListener { cb(Gcj02(it.latitude, it.longitude)) } })
        tencent?.map?.setOnMapClickListener(callback?.let { cb -> com.tencent.tencentmap.mapsdk.maps.TencentMap.OnMapClickListener { cb(Gcj02(it.latitude, it.longitude)) } })
    }
    fun onCamera(callback: ((MapCamera) -> Unit)?) {
        amap?.map?.setOnCameraChangeListener(if (callback == null) null else object : com.amap.api.maps.AMap.OnCameraChangeListener {
            override fun onCameraChange(p: com.amap.api.maps.model.CameraPosition?) = Unit
            override fun onCameraChangeFinish(p: com.amap.api.maps.model.CameraPosition?) { p?.let { callback(MapCamera(Gcj02(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt)) } }
        })
        tencent?.map?.setOnCameraChangeListener(if (callback == null) null else object : com.tencent.tencentmap.mapsdk.maps.TencentMap.OnCameraChangeListener {
            override fun onCameraChange(p: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition?) = Unit
            override fun onCameraChangeFinished(p: com.tencent.tencentmap.mapsdk.maps.model.CameraPosition?) { p?.let { callback(MapCamera(Gcj02(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt)) } }
        })
    }
    fun project(point: Point): Gcj02 = amap?.map?.projection?.fromScreenLocation(point)?.let { Gcj02(it.latitude, it.longitude) }
        ?: tencent!!.map.projection.fromScreenLocation(point).let { Gcj02(it.latitude, it.longitude) }
    fun marker(point: Gcj02, title: String, label: View? = null, selected: Boolean = false): MapOverlay {
        amap?.map?.let { map ->
            val options = com.amap.api.maps.model.MarkerOptions().position(APoint(point.latitude, point.longitude)).title(title)
            if (label != null) options.icon(com.amap.api.maps.model.BitmapDescriptorFactory.fromView(label)).anchor(0.5f, 1f)
            else if (selected) options.icon(com.amap.api.maps.model.BitmapDescriptorFactory.fromResource(com.system.location.service.R.drawable.icon_selected_location_16))
            val marker = map.addMarker(options)
            return object : MapOverlay { override fun remove() { marker.remove() } }
        }
        val options = com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions().position(TPoint(point.latitude, point.longitude)).title(title)
        if (label != null) options.icon(com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromView(label)).anchor(0.5f, 1f)
        else if (selected) options.icon(com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory.fromResource(com.system.location.service.R.drawable.icon_selected_location_16))
        val marker = tencent!!.map.addMarker(options)
        return object : MapOverlay { override fun remove() { marker.remove() } }
    }
    fun line(points: List<Gcj02>, color: Int, width: Float = 10f): MapLine {
        amap?.map?.let { map ->
            val line = map.addPolyline(com.amap.api.maps.model.PolylineOptions().addAll(points.map { APoint(it.latitude, it.longitude) }).width(width).color(color))
            return object : MapLine {
                override fun remove() { line.remove() }
                override fun points(points: List<Gcj02>) { line.points = points.map { APoint(it.latitude, it.longitude) } }
            }
        }
        val line = tencent!!.map.addPolyline(com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions().addAll(points.map { TPoint(it.latitude, it.longitude) }).width(width).color(color))
        return object : MapLine {
            override fun remove() { line.remove() }
            override fun points(points: List<Gcj02>) { line.points = points.map { TPoint(it.latitude, it.longitude) } }
        }
    }
    fun currentPosition(point: Gcj02) {
        current?.remove()
        val dot = android.widget.TextView(context).apply { text = "●"; textSize = 25f; setTextColor(android.graphics.Color.rgb(30, 110, 230)) }
        current = marker(point, "当前位置", dot)
    }
    fun clear() { current = null; amap?.map?.clear(); tencent?.map?.clear() }
    fun onStart() { tencent?.onStart() }
    fun onResume() { amap?.onResume(); tencent?.onResume() }
    fun onPause() { amap?.onPause(); tencent?.onPause() }
    fun onStop() { tencent?.onStop() }
    fun onSaveInstanceState(state: Bundle) { amap?.onSaveInstanceState(state) }
    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        onClick(null); onCamera(null)
        amap?.onDestroy(); tencent?.onDestroy()
        amap = null; tencent = null; current = null; removeAllViews()
    }
}
