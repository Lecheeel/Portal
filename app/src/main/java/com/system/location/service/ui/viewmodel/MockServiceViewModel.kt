package com.system.location.service.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.system.location.service.android.coro.CoroutineController
import com.system.location.service.android.coro.CoroutineRouteMock
import com.system.location.service.ext.Loc4j
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.reportDuration
import com.system.location.service.ext.speed
import com.system.location.service.service.MockServiceHelper
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.mock.HistoricalRoute
import com.system.location.service.ui.mock.Rocker
import com.system.location.service.hook.utils.FakeLoc
import net.sf.geographiclib.Geodesic

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null


    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = GlobalScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    val lm = locationManager
                    if (lm == null) {
                        Log.e("MockServiceViewModel", "locationManager is null, skip move")
                    } else if(!MockServiceHelper.move(lm, FakeLoc.speed / (1000 / delayTime) / 0.85, FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }

//                    if (MockServiceHelper.broadcastLocation(locationManager!!)) {
//                        Log.d("MockServiceViewModel", "Broadcast location")
//                    } else {
//                        Log.e("MockServiceViewModel", "Failed to broadcast location")
//                    }
                } while (isActive)
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || routeMockJob.isCancelled) {
            routeMockCoroutine.pause()
            val delayTime = activity.reportDuration.toLong()
            routeMockJob = GlobalScope.launch {
                do {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)

                    // Issue #5 修复：路线模拟时空指针崩溃
                    // selectedRoute / locationManager / route 可能未就绪或为空，跳过本轮等待下次 tick
                    val route = selectedRoute?.route
                    if (route.isNullOrEmpty()) continue
                    val lm = locationManager ?: continue

                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        MockServiceHelper.setLocation(
                            lm,
                            route[0].first,
                            route[0].second
                        )
                        routeStage++
                    }

                    // 处理所有已到达的阶段
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val location = MockServiceHelper.getLocation(lm)
                        if (location == null) {
                            Log.e("MockServiceViewModel", "getLocation returned null")
                            break
                        }
                        val currentLat = location.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        // 判断距离是否小于1米（可根据需要调整阈值）
                        if (inverse.s12 < 1.0) {
                            // 精确设置位置到目标点并进入下一阶段
                            MockServiceHelper.setLocation(
                                lm,
                                target.first,
                                target.second
                            )
                            routeStage++
                        } else if (inverse.s12 < FakeLoc.speed / (1000 / delayTime) / 0.85) {
                            // 如果距离小于速度，直接移动到目标点
                            MockServiceHelper.setLocation(
                                lm,
                                target.first,
                                target.second
                            )
                            routeStage++

                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 重设阶段
                        routeStage = 0
                        break // 退出循环
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val location = MockServiceHelper.getLocation(lm)
                    if (location == null) {
                        Log.e("MockServiceViewModel", "getLocation returned null")
                        continue
                    }
                    val currentLat = location.first
                    val currentLon = location.second

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth")
                    if (!MockServiceHelper.move(
                            lm,
                            FakeLoc.speed / (1000 / delayTime) / 0.85,
                            azimuth
                        )
                    ) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }
                } while (isActive)
            }
        }

        return rocker
    }

    fun isServiceStart(): Boolean {
        val lm = locationManager ?: return false
        return MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(lm)
    }
}
