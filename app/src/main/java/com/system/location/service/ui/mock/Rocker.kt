package com.system.location.service.ui.mock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.system.location.service.R
import com.system.location.service.android.widget.RockerView
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.ext.rockerCoords
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/** A single application-owned overlay; it never retains an Activity or Fragment. */
@SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
class Rocker(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val root = LayoutInflater.from(context).inflate(R.layout.layout_rocker, null)
    private val rockerView = root.findViewById<RockerView>(R.id.rocker)
    private val compact = root.findViewById<View>(R.id.rocker_minimized)
    private val panel = root.findViewById<View>(R.id.rocker_panel)
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.LEFT or Gravity.TOP
        x = context.rockerCoords.first
        y = context.rockerCoords.second
    }
    var isStart = false
        private set
    var isMinimized = true
        private set
    private var autoStatus = false
    private val speedSlider = root.findViewById<Slider>(R.id.speed_slider)
    private val speedText = root.findViewById<TextView>(R.id.speed)
    private var displayedSpeed = 0.0
    private var draggingSpeed = false
    var onSpeedChanged: ((Double) -> Unit)? = null

    init {
        speedSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            changeSpeed(round(value * 10.0) / 10.0)
        }
        speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { draggingSpeed = true }
            override fun onStopTrackingTouch(slider: Slider) { draggingSpeed = false }
        })
        root.findViewById<View>(R.id.speed_down).setOnClickListener { changeSpeed(displayedSpeed - 0.1) }
        root.findViewById<View>(R.id.speed_up).setOnClickListener { changeSpeed(displayedSpeed + 0.1) }
        compact.setOnClickListener { minimize(false) }
        root.findViewById<View>(R.id.expand_menu).setOnClickListener { minimize(true) }
        attachDrag(compact)
        attachDrag(root.findViewById(R.id.move))
        root.findViewById<View>(R.id.auto).setOnClickListener {
            autoStatus = !autoStatus
            rockerView.auto(autoStatus)
            renderAutoState()
            if (!autoStatus) {
                resetMovement()
                rockerView.listener?.onFinished()
            }
        }
        renderAutoState()
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampPosition() }
    }

    fun show() {
        if (isStart) return
        minimize(true)
        windowManager.addView(root, layoutParams)
        isStart = true
        root.post { clampPosition() }
    }

    fun hide() {
        resetMovement()
        if (isStart) windowManager.removeViewImmediate(root)
        isStart = false
    }

    fun minimize(value: Boolean) {
        isMinimized = value
        compact.visibility = if (value) View.VISIBLE else View.GONE
        panel.visibility = if (value) View.GONE else View.VISIBLE
        if (isStart) windowManager.updateViewLayout(root, layoutParams)
    }

    fun setPlaybackState(phase: RuntimePhase, route: Boolean) {
        val canMove = phase == RuntimePhase.RUNNING && !route
        val status = context.getString(when (phase) {
            RuntimePhase.RUNNING -> if (route) R.string.overlay_route_running else R.string.overlay_running
            RuntimePhase.PAUSED -> R.string.overlay_paused
            else -> R.string.overlay_idle
        })
        rockerView.isEnabled = canMove
        rockerView.alpha = if (canMove) 1f else 0.55f
        rockerView.stateDescription = status
        compact.stateDescription = status
        root.findViewById<View>(R.id.move).stateDescription = status
        root.findViewById<View>(R.id.rocker_state_dot).backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, when (phase) {
                RuntimePhase.RUNNING -> R.color.green500
                RuntimePhase.PAUSED -> R.color.yellow500
                RuntimePhase.ERROR -> R.color.red500
                else -> R.color.text_tertiary
            }))
        root.findViewById<View>(R.id.auto).apply {
            isEnabled = canMove
            alpha = if (canMove) 1f else 0.4f
        }
        if (!canMove) resetMovement()
    }

    fun resetMovement() {
        autoStatus = false
        rockerView.auto(false)
        rockerView.reset()
        renderAutoState()
    }

    private fun renderAutoState() {
        root.findViewById<ImageView>(R.id.auto).apply {
            isSelected = autoStatus
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context,
                if (autoStatus) R.color.primary else R.color.text_secondary))
            stateDescription = context.getString(if (autoStatus) R.string.overlay_auto_on else R.string.overlay_auto_off)
        }
    }

    fun setRockerListener(listener: RockerView.Companion.OnMoveListener) {
        rockerView.listener = listener
    }

    fun setSpeedState(route: Boolean, speed: Double) {
        val title = context.getString(if (route) R.string.overlay_route_speed else R.string.rocker_speed)
        speedSlider.contentDescription = title
        if (!draggingSpeed) renderSpeed(speed)
    }

    private fun changeSpeed(speed: Double) {
        val value = (round(speed * 100.0) / 100.0).coerceIn(0.0, 1000.0)
        renderSpeed(value)
        onSpeedChanged?.invoke(value)
    }

    private fun renderSpeed(speed: Double) {
        displayedSpeed = speed.coerceIn(0.0, 1000.0)
        speedText.text = context.getString(R.string.overlay_speed_number, displayedSpeed)
        speedText.contentDescription = context.getString(R.string.overlay_speed_value, displayedSpeed)
        root.findViewById<TextView>(R.id.speed_kmh).text = context.getString(R.string.speed_kmh, displayedSpeed * 3.6)
        // Keep a useful range for walking/driving without truncating a faster saved scenario.
        val upper = maxOf(50.0, ceil(displayedSpeed / 10.0) * 10.0).toFloat()
        if (speedSlider.value > upper) speedSlider.value = upper
        speedSlider.valueTo = upper
        if (!draggingSpeed) speedSlider.value = displayedSpeed.toFloat()
        root.findViewById<View>(R.id.speed_down).apply {
            isEnabled = displayedSpeed > 0
            alpha = if (isEnabled) 1f else 0.35f
        }
        root.findViewById<View>(R.id.speed_up).apply {
            isEnabled = displayedSpeed < 1000
            alpha = if (isEnabled) 1f else 0.35f
        }
    }

    private fun attachDrag(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var dragging = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging && isStart) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        clampPosition()
                        windowManager.updateViewLayout(root, layoutParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    context.rockerCoords = layoutParams.x to layoutParams.y
                    if (!dragging) view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> context.rockerCoords = layoutParams.x to layoutParams.y
            }
            true
        }
    }

    private fun clampPosition() {
        if (!isStart) return
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val maxX = (metrics.bounds.width() - insets.left - insets.right - root.width).coerceAtLeast(0)
        val maxY = (metrics.bounds.height() - insets.top - insets.bottom - root.height).coerceAtLeast(0)
        val x = layoutParams.x.coerceIn(0, maxX)
        val y = layoutParams.y.coerceIn(0, maxY)
        if (x != layoutParams.x || y != layoutParams.y) {
            layoutParams.x = x
            layoutParams.y = y
            windowManager.updateViewLayout(root, layoutParams)
            context.rockerCoords = x to y
        }
    }
}
