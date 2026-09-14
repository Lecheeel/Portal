package com.system.location.service.amap

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.*
import com.system.location.service.core.geo.Gcj02
import com.system.location.service.core.planning.PlanRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A new SDK request object per plan prevents listener replacement from mixing responses. */
class AMapRoutePlanner(private val context: Context) {
    suspend fun plan(request: PlanRequest): List<List<Gcj02>> = suspendCancellableCoroutine { continuation ->
        try {
            val search = RouteSearch(context)
            search.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onWalkRouteSearched(result: WalkRouteResult?, code: Int) {
                    if (!continuation.isActive) return
                    runCatching {
                        check(code == 1000) { "高德步行规划失败（$code），请检查网络、地图 Key 与授权" }
                        // paths are alternatives; steps within the selected path are sequential.
                        val path = result?.paths?.firstOrNull() ?: error("高德未返回可用路线")
                        path.steps.orEmpty().map { step -> step.polyline.orEmpty().map { Gcj02(it.latitude, it.longitude) } }
                    }.onSuccess { continuation.resume(it) }.onFailure { continuation.resumeWithException(it) }
                }
                override fun onBusRouteSearched(result: BusRouteResult?, code: Int) = Unit
                override fun onDriveRouteSearched(result: DriveRouteResult?, code: Int) = Unit
                override fun onRideRouteSearched(result: RideRouteResult?, code: Int) = Unit
            })
            val endpoints = RouteSearch.FromAndTo(LatLonPoint(request.start.latitude, request.start.longitude),
                LatLonPoint(request.end.latitude, request.end.longitude))
            search.calculateWalkRouteAsyn(RouteSearch.WalkRouteQuery(endpoints))
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
    }
}
