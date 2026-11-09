package com.system.location.service.maps

import android.content.Context
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.geocoder.*
import com.amap.api.services.core.LatLonPoint
import com.system.location.service.BuildConfig
import com.system.location.service.amap.AMapRoutePlanner
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.planning.PlanRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlaceSuggestion(val name: String, val address: String, val point: Gcj02)
data class PlaceAddress(val title: String, val address: String)

/** Provider captured with the map view: never fall back to a competing service. */
class MapServices(private val context: Context, val provider: MapProvider = context.mapProvider) {
    suspend fun suggestions(query: String, city: String = ""): List<PlaceSuggestion> {
        if (provider == MapProvider.TENCENT) {
            val data = get("place/v1/suggestion/", mapOf("keyword" to query, "region" to city.ifBlank { "全国" }, "region_fix" to "0")).getJSONArray("data")
            return (0 until data.length()).mapNotNull { i ->
                val item = data.getJSONObject(i)
                val p = item.optJSONObject("location") ?: return@mapNotNull null
                PlaceSuggestion(item.optString("title"), item.optString("address"), Gcj02(p.getDouble("lat"), p.getDouble("lng")))
            }
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                Inputtips(context, InputtipsQuery(query, city)).apply {
                    setInputtipsListener { tips, code ->
                        if (!continuation.isActive) return@setInputtipsListener
                        if (code != 1000) continuation.resumeWithException(IllegalStateException("高德搜索失败（$code）"))
                        else continuation.resume(tips.orEmpty().mapNotNull { tip -> tip.point?.let {
                            PlaceSuggestion(tip.name.orEmpty(), tip.address.orEmpty(), Gcj02(it.latitude, it.longitude))
                        } })
                    }
                    requestInputtipsAsyn()
                }
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }
    suspend fun address(point: Gcj02): PlaceAddress {
        if (provider == MapProvider.TENCENT) {
            val result = get("geocoder/v1/", mapOf("location" to "${point.latitude},${point.longitude}", "get_poi" to "0")).getJSONObject("result")
            val address = result.optString("address")
            return PlaceAddress(result.optJSONObject("formatted_addresses")?.optString("recommend").orEmpty().ifBlank { address }, address)
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                GeocodeSearch(context).apply {
                    setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                        override fun onGeocodeSearched(result: GeocodeResult?, code: Int) = Unit
                        override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                            if (!continuation.isActive) return
                            val address = result?.regeocodeAddress
                            if (code != 1000 || address == null) continuation.resumeWithException(IllegalStateException("高德地址查询失败（$code）"))
                            else continuation.resume(PlaceAddress(address.pois?.firstOrNull()?.title ?: address.district.orEmpty(), address.formatAddress.orEmpty()))
                        }
                    })
                    getFromLocationAsyn(RegeocodeQuery(LatLonPoint(point.latitude, point.longitude), 200f, GeocodeSearch.AMAP))
                }
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }
    suspend fun walk(request: PlanRequest): List<List<Gcj02>> {
        if (provider == MapProvider.AMAP) return AMapRoutePlanner(context).plan(request)
        val result = get("direction/v1/walking/", mapOf("from" to "${request.start.latitude},${request.start.longitude}",
            "to" to "${request.end.latitude},${request.end.longitude}"))
        val routes = result.getJSONObject("result").getJSONArray("routes")
        check(routes.length() > 0) { "腾讯未返回可用步行路线" }
        val raw = routes.getJSONObject(0).getJSONArray("polyline")
        val values = (0 until raw.length()).map { raw.getDouble(it) }.toMutableList()
        return listOf(TencentPolyline.decode(values))
    }
    private suspend fun get(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        check(provider == MapProvider.TENCENT && com.system.location.service.privacy.TencentPrivacy.ready(context)) { "腾讯地图尚未授权" }
        check(BuildConfig.TENCENT_MAP_KEY.isNotBlank()) { "腾讯地图 Key 未配置" }
        val query = (params + ("key" to BuildConfig.TENCENT_MAP_KEY)).entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val connection = URI("https://apis.map.qq.com/ws/$path?$query").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        try {
            ensureActive()
            check(connection.responseCode == 200) { "腾讯地图网络请求失败（HTTP ${connection.responseCode}）" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            ensureActive()
            val result = JSONObject(body)
            val status = result.optInt("status", -1)
            check(status == 0) { "腾讯地图请求失败（$status）：${result.optString("message")}" }
            result
        } finally { connection.disconnect() }
    }
}

/** Tencent walking responses delta-encode each coordinate from the preceding same-axis value. */
object TencentPolyline {
    fun decode(encoded: List<Double>): List<Gcj02> {
        require(encoded.size >= 4 && encoded.size % 2 == 0) { "腾讯步行路线坐标无效" }
        val values = encoded.toMutableList()
        for (i in 2 until values.size) values[i] = values[i - 2] + values[i] / 1_000_000.0
        return values.chunked(2).map { Gcj02(it[0], it[1]) }
    }
}
