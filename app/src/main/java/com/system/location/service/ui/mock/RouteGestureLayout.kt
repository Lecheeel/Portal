package com.system.location.service.ui.mock

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/** Single fingers draw. A second finger cancels the uncommitted stroke and hands a complete
 * gesture stream to the map, including DOWN; drawing resumes only on the next fresh gesture. */
class RouteGestureLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var drawingEnabled = false
    var onStrokeTouch: ((MotionEvent) -> Unit)? = null
    private var navigating = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) navigating = false
        if (!navigating && event.pointerCount > 1) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            onStrokeTouch?.invoke(cancel)
            cancel.recycle()
            navigating = true
            val properties = MotionEvent.PointerProperties().also { event.getPointerProperties(0, it) }
            val coordinates = MotionEvent.PointerCoords().also { event.getPointerCoords(0, it) }
            val down = MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_DOWN,
                1, arrayOf(properties), arrayOf(coordinates), event.metaState, event.buttonState,
                event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
            super.dispatchTouchEvent(down)
            down.recycle()
        }
        if (navigating) super.dispatchTouchEvent(event) else onStrokeTouch?.invoke(event)
        return true
    }
}
