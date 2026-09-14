package com.system.location.service

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ui.mock.RouteGestureLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteGestureTest {
    @Test fun secondFingerCancelsStrokeAndMapReceivesCompleteGesture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val strokes = mutableListOf<Int>()
            val mapEvents = mutableListOf<Int>()
            val layout = RouteGestureLayout(instrumentation.targetContext).apply {
                drawingEnabled = true
                onStrokeTouch = { strokes.add(it.actionMasked) }
                addView(View(context).apply { setOnTouchListener { _, e -> mapEvents.add(e.actionMasked); true } },
                    FrameLayout.LayoutParams(500, 500))
                measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                layout(0, 0, 500, 500)
            }
            var time = android.os.SystemClock.uptimeMillis()
            val downTime = time
            fun dispatch(action: Int, count: Int = 1) {
                val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { x = 100f + i * 100; y = 100f; pressure = 1f; size = 1f } }
                val event = MotionEvent.obtain(downTime, ++time, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                layout.dispatchTouchEvent(event)
                event.recycle()
            }
            dispatch(MotionEvent.ACTION_DOWN)
            dispatch(MotionEvent.ACTION_MOVE)
            dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            dispatch(MotionEvent.ACTION_MOVE, 2)
            dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            dispatch(MotionEvent.ACTION_MOVE)
            dispatch(MotionEvent.ACTION_UP)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), strokes)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), mapEvents)
            mapEvents.clear(); strokes.clear()
            dispatch(MotionEvent.ACTION_DOWN); dispatch(MotionEvent.ACTION_MOVE); dispatch(MotionEvent.ACTION_UP)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), strokes)
            assertTrue(mapEvents.isEmpty())
            layout.drawingEnabled = false
            strokes.clear()
            dispatch(MotionEvent.ACTION_DOWN); dispatch(MotionEvent.ACTION_UP)
            assertTrue(strokes.isEmpty())
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), mapEvents)
        }
    }
}
