package com.system.location.service.ui.mock

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowManager
import com.system.location.service.LocationServiceApp
import com.system.location.service.R
import com.system.location.service.android.widget.RockerView
import com.system.location.service.android.window.OverlayUtils
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Main-thread owner of the overlay, independent of navigation and playback lifetime. */
object RockerOverlay {
    private val context get() = LocationServiceApp.appContext
    private val preferences get() = context.getSharedPreferences("rocker_overlay", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rocker: Rocker? = null
    private var observing = false
    private var moving = false
    private var locked = false
    private var bearing = 0.0
    val isEnabled get() = preferences.getBoolean("enabled", true)
    val isVisible get() = rocker?.isStart == true

    fun refresh(): Boolean {
        if (!isEnabled || !OverlayUtils.hasOverlayPermissions(context)) {
            moving = false
            locked = false
            publishMotion()
            rocker?.hide()
            return false
        }
        if (!observing) {
            observing = true
            scope.launch {
                ScenarioRuntime.state.map { Triple(it.phase, it.scenarioId, it.startedAt) }
                    .distinctUntilChanged().collect {
                        moving = false
                        locked = false
                        if (it.first == RuntimePhase.PAUSED) publishMotion()
                        rocker?.resetMovement()
                        updatePlaybackState()
                    }
            }
        }
        return try {
            val window = rocker ?: Rocker(ContextThemeWrapper(
                context.createDisplayContext(context.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY))
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.AppTheme
            )).also { view ->
                rocker = view
                view.setRockerListener(object : RockerView.Companion.OnMoveListener {
                    override fun onStarted() {
                        bearing = ScenarioRuntime.state.value.sample?.bearing?.toDouble() ?: bearing
                        moving = true
                    }
                    override fun onAngle(angle: Double) {
                        bearing = angle
                        publishMotion()
                    }
                    override fun onLockChanged(isLocked: Boolean) { locked = isLocked }
                    override fun onFinished() {
                        if (!locked) {
                            moving = false
                            publishMotion()
                        }
                    }
                })
            }
            updatePlaybackState()
            window.show()
            true
        } catch (error: RuntimeException) {
            Log.e("RockerOverlay", "Unable to show floating rocker", error)
            false
        }
    }

    fun setEnabled(enabled: Boolean): Boolean {
        preferences.edit().putBoolean("enabled", enabled).apply()
        if (enabled) return refresh()
        moving = false
        locked = false
        publishMotion()
        rocker?.hide()
        return true
    }

    private fun publishMotion() {
        if (ScenarioRuntime.state.value.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)) {
            ScenarioRuntime.motion(bearing, moving)
        }
    }

    private fun updatePlaybackState() {
        val state = ScenarioRuntime.state.value
        val phase = state.phase
        rocker?.setPlaybackState(phase == RuntimePhase.RUNNING && state.routeId == null, when (phase) {
            RuntimePhase.RUNNING -> if (state.routeId == null) "模拟中 · 拖动摇杆移动" else "路线播放中"
            RuntimePhase.PAUSED -> "模拟已暂停"
            else -> "未在模拟 · 请在主页启动"
        })
    }
}
