package com.system.location.service.core.geo

object CoordinateTransform {
    fun toWgs84(point: Gcj02): Wgs84 = gcj2wgs(point.latitude, point.longitude).let { Wgs84(it.first, it.second) }
    fun toGcj02(point: Wgs84): Gcj02 = wgs2gcj(point.latitude, point.longitude).let { Gcj02(it.first, it.second) }
    fun toBd09(point: Gcj02): Bd09 {
        val x = point.longitude
        val y = point.latitude
        val z = kotlin.math.sqrt(x * x + y * y) + 0.00002 * kotlin.math.sin(y * X_PI)
        val theta = kotlin.math.atan2(y, x) + 0.000003 * kotlin.math.cos(x * X_PI)
        return Bd09(z * kotlin.math.sin(theta) + 0.006, z * kotlin.math.cos(theta) + 0.0065)
    }
    fun toGcj02(point: Bd09): Gcj02 {
        val x = point.longitude - 0.0065
        val y = point.latitude - 0.006
        val z = kotlin.math.sqrt(x * x + y * y) - 0.00002 * kotlin.math.sin(y * X_PI)
        val theta = kotlin.math.atan2(y, x) - 0.000003 * kotlin.math.cos(x * X_PI)
        return Gcj02(z * kotlin.math.sin(theta), z * kotlin.math.cos(theta))
    }

    private const val PI = Math.PI
    private const val X_PI = PI * 3000.0 / 180.0
    private const val A = 6378245.0               // 长半轴
    private const val EE = 0.00669342162296594326 // 偏心率平方

    private fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var wLat = lat
        var wLon = lon
        repeat(3) {
            val (dLat, dLon) = delta(wLat, wLon)
            wLat = lat - dLat
            wLon = lon - dLon
        }
        return wLat to wLon
    }

    private fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> {
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
