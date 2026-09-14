package com.system.location.service.ext

import com.amap.api.location.AMapLocation
import com.amap.api.maps.model.LatLng

val LatLng.wgs84: Pair<Double, Double>
    get() = Loc4j.gcj2wgs(latitude, longitude)

val AMapLocation.wgs84: Pair<Double, Double>
    get() = Loc4j.gcj2wgs(latitude, longitude)

val Pair<Double, Double>.gcj02: LatLng
    get() = Loc4j.wgs2gcj(first, second).let { LatLng(it.first, it.second) }

/**
 * GCJ-02 <-> WGS-84 坐标转换（纯 Kotlin 实现，替代百度 SDK 的 Jni.coorEncrypt）。
 * 标准偏移算法：对 GCJ-02 与 WGS-84 差值做迭代逼近，精度约 1e-6 度（<1m）。
 */
object Loc4j {
    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> =
        com.system.location.service.core.geo.CoordinateTransform.toWgs84(
            com.system.location.service.core.geo.Gcj02(lat, lon)).let { it.latitude to it.longitude }
    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> =
        com.system.location.service.core.geo.CoordinateTransform.toGcj02(
            com.system.location.service.core.geo.Wgs84(lat, lon)).let { it.latitude to it.longitude }
}