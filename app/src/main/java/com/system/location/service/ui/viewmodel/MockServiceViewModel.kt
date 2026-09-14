package com.system.location.service.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.location.service.LocationServiceApp
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.scenario.*
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.mock.HistoricalLocation
import com.system.location.service.ui.mock.HistoricalRoute
import com.system.location.service.ui.mock.Rocker
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.core.repository.SavedLocation

/** UI selections and event dispatch only. The service owns the running frozen scene. */
class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    var isRockerLocked = false
    var locationManager: LocationManager? = null
    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null
    val runtimeState = ScenarioRuntime.state
    private var bearing = 0.0
    private var moving = false

    fun initRocker(activity: Activity): Rocker {
        if (::rocker.isInitialized && rocker.isStart) rocker.hide()
        rocker = Rocker(activity)
        return rocker
    }
    private fun profile() = with(LocationServiceApp.appContext) {
        MovementProfile(speed, altitude, accuracy.coerceAtLeast(0.1f), reportDuration.toLong().coerceIn(50, 1000))
    }
    suspend fun startPoint(point: Pair<Double, Double>, name: String = "单点场景"): Boolean {
        moving = false
        return ScenarioRuntime.start(Scenario(UUID.randomUUID().toString(), name.ifBlank { "单点场景" },
            point = Wgs84(point.first, point.second), profile = profile())).await()
    }
    suspend fun startRoute(route: HistoricalRoute): Boolean {
        moving = false
        val id = route.id
        return ScenarioRuntime.start(Scenario(id, route.name,
            route = Route(id, route.name, route.route.map { Wgs84(it.first, it.second) }), mode = route.mode, profile = profile())).await()
    }
    suspend fun stopScenario() = ScenarioRuntime.stop().await()
    fun pauseScenario() { ScenarioRuntime.pause() }
    fun resumeScenario() { ScenarioRuntime.resume() }
    fun setBearing(value: Double) { bearing = value; ScenarioRuntime.motion(bearing, moving) }
    fun setMoving(value: Boolean) { moving = value; ScenarioRuntime.motion(bearing, moving) }
    fun pauseMovement() = setMoving(false)
    fun isServiceStart() = runtimeState.value.isActive
    fun isPaused() = runtimeState.value.phase == RuntimePhase.PAUSED
    fun failureMessage() = runtimeState.value.error?.let { "${it.stage}: ${it.reason}\n${it.suggestion}" } ?: "场景启动失败"
    fun saveLocation(name: String, address: String, latitude: Double, longitude: Double) = viewModelScope.async(Dispatchers.IO) {
        runCatching {
            LibraryRepositories.ensureMigrated()
            LibraryRepositories.locations.create(SavedLocation(UUID.randomUUID().toString(), name, Wgs84(latitude, longitude), address))
        }
    }
    override fun onCleared() {
        // Destroying a UI must not pause a route or stop its service.
        if (::rocker.isInitialized && rocker.isStart) rocker.hide()
        super.onCleared()
    }
}
