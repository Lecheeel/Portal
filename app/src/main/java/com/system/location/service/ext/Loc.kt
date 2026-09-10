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
    private const val PI = Math.PI
    private const val X_PI = PI * 3000.0 / 180.0
    private const val A = 6378245.0               // 长半轴
    private const val EE = 0.00669342162296594326 // 偏心率平方

    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> {
        // 迭代求逆：wgs + delta(wgs) ≈ gcj
        var wLat = lat
        var wLon = lon
        repeat(3) {
            val (dLat, dLon) = delta(wLat, wLon)
            wLat = lat - dLat
            wLon = lon - dLon
        }
        return wLat to wLon
    }

    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (dLat, dLon) = delta(lat, lon)
        return lat + dLat to lon + dLon
    }

    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
        return dLat to dLon
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
                0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
                0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }
}
