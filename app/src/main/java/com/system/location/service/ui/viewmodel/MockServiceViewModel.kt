package com.system.location.service.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.android.coro.CoroutineController
import com.system.location.service.android.coro.CoroutineRouteMock
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.reportDuration
import com.system.location.service.ext.speed
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.service.MockServiceHelper
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.mock.HistoricalRoute
import com.system.location.service.ui.mock.Rocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sf.geographiclib.Geodesic

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private var movementJob: Job? = null
    private val movementMutex = Mutex()
    var isRockerLocked = false
    @Volatile var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()
    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null) MockServiceHelper.tryInitService(value)
        }
    var selectedLocation: HistoricalLocation? = null
    @Volatile var selectedRoute: HistoricalRoute? = null
        set(value) {
            if (field != value) {
                routeMockCoroutine.pause()
                routeStage = 0
            }
            field = value
        }

    fun pauseMovement() {
        rockerCoroutineController.pause()
        routeMockCoroutine.pause()
    }

    suspend fun stopMovement() {
        pauseMovement()
        movementMutex.withLock {
            pauseMovement()
            routeStage = 0
        }
        withContext(Dispatchers.Main) {
            if (::rocker.isInitialized && rocker.autoStatus) rocker.autoStatus = false
        }
    }

    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) rocker = Rocker(activity)
        val context = activity.applicationContext
        FakeLoc.speed = context.speed
        FakeLoc.altitude = context.altitude
        FakeLoc.accuracy = context.accuracy
        if (movementJob?.isActive != true) {
            movementJob = viewModelScope.launch(Dispatchers.IO) {
                var previousTick = System.nanoTime()
                var previousMode = 0
                while (isActive) {
                    delay(context.reportDuration.toLong())
                    val now = System.nanoTime()
                    val elapsed = ((now - previousTick) / 1_000_000_000.0).coerceIn(0.0, 1.0)
                    previousTick = now
                    val mode = when {
                        !rockerCoroutineController.isPaused -> 1
                        !routeMockCoroutine.isPaused -> 2
                        else -> 0
                    }
                    if (mode == 0) { previousMode = 0; continue }
                    val lm = locationManager ?: continue
                    if (!MockServiceHelper.isMockStart(lm)) {
                        pauseMovement()
                        previousMode = 0
                        continue
                    }
                    // Never charge paused time to the first movement after a mode switch.
                    if (mode != previousMode) { previousMode = mode; continue }
                    val distance = context.speed * elapsed
                    movementMutex.withLock {
                        if (mode == 1 && !rockerCoroutineController.isPaused) {
                            routeMockCoroutine.pause()
                            if (!MockServiceHelper.move(lm, distance, FakeLoc.bearing)) {
                                Log.w("MockServiceViewModel", "Move failed")
                            }
                        } else if (mode == 2 && !routeMockCoroutine.isPaused) {
                            advanceRoute(lm, distance)
                        }
                    }
                }
            }
        }
        return rocker
    }

    private suspend fun advanceRoute(lm: LocationManager, distance: Double) {
        val route = selectedRoute?.route ?: return
        if (route.size < 2) return
        var current = MockServiceHelper.getLocation(lm) ?: return
        if (routeStage == 0) {
            if (!MockServiceHelper.setLocation(lm, route.first().first, route.first().second)) return
            current = route.first()
            routeStage = 1
        }
        var remaining = distance
        while (routeStage < route.size) {
            if (routeMockCoroutine.isPaused || selectedRoute?.route !== route) return
            val target = route[routeStage]
            val inverse = Geodesic.WGS84.Inverse(current.first, current.second, target.first, target.second)
            if (inverse.s12 <= remaining || inverse.s12 < 0.001) {
                if (!MockServiceHelper.setLocation(lm, target.first, target.second)) return
                remaining = (remaining - inverse.s12).coerceAtLeast(0.0)
                current = target
                routeStage++
            } else {
                if (remaining > 0) MockServiceHelper.move(lm, remaining, (inverse.azi1 + 360) % 360)
                return
            }
        }
        routeMockCoroutine.pause()
        routeStage = 0
        withContext(Dispatchers.Main) { rocker.autoStatus = false }
    }

    override fun onCleared() {
        pauseMovement()
        if (::rocker.isInitialized && rocker.isStart) rocker.hide()
        super.onCleared()
    }

    fun isServiceStart(): Boolean {
        val lm = locationManager ?: return false
        return MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(lm)
    }
}
