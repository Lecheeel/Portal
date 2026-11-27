package com.system.location.service.ui.mock

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import com.system.location.service.R
import com.system.location.service.android.widget.RockerView
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.rockerCoords
import com.system.location.service.ui.PortalDialog
import kotlin.math.round

/** A single application-owned Compose overlay; it never retains an Activity or Fragment. */
class Rocker(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private lateinit var root: ComposeView
    private val rockerView = RockerView(context, null).apply { id = R.id.rocker }
    private var owner: OverlayComposeOwner? = null
    private val targetMenu = RockerTargetMenu(context)
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.LEFT or Gravity.TOP
        x = context.rockerCoords.first
        y = context.rockerCoords.second
    }
    var isStart = false
        private set
    var isMinimized by mutableStateOf(true)
        private set
    private var speedExpanded by mutableStateOf(false)
    private var displayedSpeed by mutableStateOf(0.0)
    private var route by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var canMove by mutableStateOf(false)
    private var targetsEnabled by mutableStateOf(true)
    private var speedInput: PortalDialog? = null
    var onSpeedChanged: ((Double) -> Unit)? = null

    fun show() {
        if (isStart) return
        minimize(true)
        // A detached ComposeView retains its AndroidComposeView and window recomposer linkage.
        // Each attachment needs a fresh view as well as a fresh lifecycle owner.
        root = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampPosition() }
        }
        val session = OverlayComposeOwner().also { it.attach(root); owner = it }
        root.setContent {
            PortalTheme {
                RockerPanel(isMinimized, speedExpanded, status, displayedSpeed, route, canMove,
                    targetsEnabled, rockerView, ::minimize, { speedExpanded = !speedExpanded },
                    { resetMovement(); rockerView.listener?.onFinished(); targetMenu.open() },
                    ::changeSpeed, ::editSpeed, ::drag, ::savePosition)
            }
        }
        try {
            windowManager.addView(root, layoutParams)
            isStart = true
            root.post { clampPosition() }
        } catch (failure: RuntimeException) {
            root.disposeComposition()
            session.close()
            owner = null
            throw failure
        }
    }

    fun hide() {
        targetMenu.close()
        speedInput?.dismiss()
        resetMovement()
        if (isStart) windowManager.removeViewImmediate(root)
        isStart = false
        if (::root.isInitialized) root.disposeComposition()
        owner?.close()
        owner = null
    }

    fun minimize(value: Boolean) {
        if (value) {
            speedExpanded = false
            speedInput?.dismiss()
        }
        isMinimized = value
        if (isStart) root.post { clampPosition() }
    }

    fun setPlaybackState(phase: RuntimePhase, route: Boolean,
        holdingPosition: com.system.location.service.core.runtime.RouteEndPosition? = null) {
        canMove = phase == RuntimePhase.RUNNING && !route
        status = if (phase == RuntimePhase.HOLDING) {
            if (holdingPosition == com.system.location.service.core.runtime.RouteEndPosition.START) "停留在起点" else "停留在终点"
        } else context.getString(when (phase) {
            RuntimePhase.RUNNING -> if (route) R.string.overlay_route_running else R.string.overlay_running
            RuntimePhase.PAUSED -> R.string.overlay_paused
            else -> R.string.overlay_idle
        })
        targetsEnabled = phase !in setOf(RuntimePhase.PREPARING, RuntimePhase.READY, RuntimePhase.STOPPING)
        rockerView.isEnabled = canMove
        ViewCompat.setStateDescription(rockerView, status)
        if (!canMove) resetMovement()
    }

    fun resetMovement() { rockerView.auto(false); rockerView.reset() }
    fun setRockerListener(listener: RockerView.Companion.OnMoveListener) { rockerView.listener = listener }
    fun setSpeedState(route: Boolean, speed: Double) {
        this.route = route
        displayedSpeed = speed.coerceIn(0.0, 1000.0)
    }
    private fun changeSpeed(speed: Double) {
        displayedSpeed = (round(speed * 100.0) / 100.0).coerceIn(0.0, 1000.0)
        onSpeedChanged?.invoke(displayedSpeed)
    }
    private fun editSpeed() {
        if (speedInput?.isShowing == true) return
        resetMovement()
        rockerView.listener?.onFinished()
        speedInput = showRockerSpeedInput(context, displayedSpeed, ::changeSpeed)?.also { dialog ->
            dialog.setOnDismissListener { if (speedInput === dialog) speedInput = null }
        }
    }
    private fun drag(dx: Float, dy: Float) {
        if (!isStart) return
        layoutParams.x += dx.toInt()
        layoutParams.y += dy.toInt()
        clampPosition()
        windowManager.updateViewLayout(root, layoutParams)
    }
    private fun savePosition() { context.rockerCoords = layoutParams.x to layoutParams.y }
    @Suppress("DEPRECATION")
    private fun clampPosition() {
        if (!isStart) return
        val available = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Point(metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        } else {
            Point().also { windowManager.defaultDisplay.getSize(it) }
        }
        val maxX = (available.x - root.width).coerceAtLeast(0)
        val maxY = (available.y - root.height).coerceAtLeast(0)
        val x = layoutParams.x.coerceIn(0, maxX)
        val y = layoutParams.y.coerceIn(0, maxY)
        if (x != layoutParams.x || y != layoutParams.y) {
            layoutParams.x = x
            layoutParams.y = y
            windowManager.updateViewLayout(root, layoutParams)
            savePosition()
        }
    }
}
