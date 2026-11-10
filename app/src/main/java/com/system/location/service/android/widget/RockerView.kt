package com.system.location.service.android.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.system.location.service.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import com.system.location.service.core.playback.JoystickInput
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt


// https://github.com/lqxue/RockerView/blob/master/app/src/main/java/com/zcsj/rockerview/RockerView.java
@SuppressLint("UseCompatLoadingForDrawables")
class RockerView(context: Context, attributeSet: AttributeSet): View(context, attributeSet) {
    private var mAreaBackgroundPaint: Paint
    private var mRockerPaint: Paint
    private var mRockerPosition: Point
    private val directionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mCenterPoint: Point
    private val lockPaint = Paint()
    private val lockBitmap: Bitmap
    private var handler = Handler(Looper.getMainLooper())
    private var lockRunnable: Runnable? = null
    private var returnAnimator: ValueAnimator? = null

    private var mAreaRadius = 0.0f
    private var mRockerInnerCircleRadius = 0.0f
    private var mRockerColor = 0
    private var mAreaColor = 0
    private var isLocked = AtomicBoolean(false)
    private var isAuto = AtomicBoolean(false)
    var listener: OnMoveListener? = null
    private var robotBitmap: Bitmap? = null
    private val density = resources.displayMetrics.density
    private val secondaryColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val borderColor = ContextCompat.getColor(context, R.color.card_border)
    private val gripColor = ContextCompat.getColor(context, R.color.on_primary)


    init {
        val typedArray = context.obtainStyledAttributes(attributeSet, R.styleable.RockerView)
        val areaBackground = typedArray.getDrawable(R.styleable.RockerView_areaBackground)
        if (null != areaBackground) {
            if (areaBackground is ColorDrawable) {
                mAreaColor = areaBackground.color
            } else {
                throw IllegalArgumentException("areaBackground only support ColorDrawable")
            }
        }
        val rockerBackground = typedArray.getDrawable(R.styleable.RockerView_rockerBackground)
        if (null != rockerBackground) {
            if (rockerBackground is ColorDrawable) {
                mRockerColor = rockerBackground.color
            } else {
                throw IllegalArgumentException("rockerBackground only support ColorDrawable")
            }
        }
        typedArray.recycle()

        mAreaBackgroundPaint = Paint()
        mAreaBackgroundPaint.isAntiAlias = true

        mRockerPaint = Paint()
        mRockerPaint.isAntiAlias = true
        mRockerPaint.color = mRockerColor
        robotBitmap = ResourcesCompat.getDrawable(resources, R.drawable.baseline_robot_24, context.theme)
            ?.mutate()?.apply { setTint(gripColor) }?.toBitmap()

        mCenterPoint = Point()
        mRockerPosition = Point()

        directionPaint.textAlign = Paint.Align.CENTER
        directionPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeCap = Paint.Cap.ROUND
        lockBitmap = ResourcesCompat.getDrawable(resources, R.drawable.baseline_lock_24, context.theme)!!
            .mutate().apply { setTint(gripColor) }.toBitmap()

        lockPaint.isAntiAlias = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getMySize(widthMeasureSpec)
        val height = getMySize(heightMeasureSpec)
        val min = width.coerceAtMost(height)
        setMeasuredDimension(min, min)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paddingLeft = paddingLeft
        val paddingRight = paddingRight
        val paddingTop = paddingTop
        val paddingBottom = paddingBottom
        val inset = (DEFAULT_PADDING_SIZE * density).toInt()
        val width = (width - paddingLeft - paddingRight - inset).coerceAtLeast(1)
        val height = (height - paddingBottom - paddingTop - inset).coerceAtLeast(1)
        val cx = width / 2 + paddingLeft + inset / 2
        val cy = height / 2 + paddingTop + inset / 2
        mCenterPoint[cx] = cy
        mAreaRadius = (min(width.toDouble(), height.toDouble()) / 2).toFloat()


        if (0 == mRockerPosition.x || 0 == mRockerPosition.y) {
            mRockerPosition[mCenterPoint.x] = mCenterPoint.y
        }

        mAreaBackgroundPaint.color = mAreaColor
        canvas.drawCircle(mCenterPoint.x.toFloat(), mCenterPoint.y.toFloat(), mAreaRadius, mAreaBackgroundPaint)

        ringPaint.color = borderColor
        ringPaint.strokeWidth = density
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), mAreaRadius - density / 2, ringPaint)
        ringPaint.alpha = 100
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), mAreaRadius * 0.45f, ringPaint)
        ringPaint.alpha = 255

        drawDirection(canvas)

        mRockerInnerCircleRadius = mAreaRadius * 0.24f
        detailPaint.color = android.graphics.Color.argb(18, 0, 0, 0)
        canvas.drawCircle(mRockerPosition.x.toFloat(), mRockerPosition.y + 2 * density,
            mRockerInnerCircleRadius + density, detailPaint)
        canvas.drawCircle(
            mRockerPosition.x.toFloat(),
            mRockerPosition.y.toFloat(), mRockerInnerCircleRadius, mRockerPaint
        )
        ringPaint.color = gripColor
        ringPaint.alpha = 70
        ringPaint.strokeWidth = density
        canvas.drawCircle(mRockerPosition.x.toFloat(), mRockerPosition.y.toFloat(),
            mRockerInnerCircleRadius - 2 * density, ringPaint)
        ringPaint.alpha = 255
        if (isLocked.get()) {
            drawLock(canvas)
        } else if (isAuto.get()) {
            robotBitmap?.let {
                val half = mRockerInnerCircleRadius * 0.55f
                canvas.drawBitmap(it, null, RectF(mRockerPosition.x - half, mRockerPosition.y - half,
                    mRockerPosition.x + half, mRockerPosition.y + half), lockPaint)
            }
        } else {
            detailPaint.color = gripColor
            for (offset in -1..1) {
                canvas.drawCircle(mRockerPosition.x + offset * 4 * density,
                    mRockerPosition.y.toFloat(), density, detailPaint)
            }
        }
    }

    private fun drawDirection(canvas: Canvas, circleRadius: Float = mAreaRadius, circleX: Float = mCenterPoint.x.toFloat(), circleY: Float = mCenterPoint.y.toFloat()) {
        ringPaint.strokeWidth = density
        for (index in 0 until 24) {
            val angle = Math.toRadians(index * 15.0 - 90.0)
            val major = index % 6 == 0
            val inner = circleRadius * if (major) 0.80f else 0.85f
            val outer = circleRadius * 0.90f
            ringPaint.color = if (index == 0) mRockerColor else secondaryColor
            ringPaint.alpha = if (major) 220 else 80
            canvas.drawLine(circleX + inner * cos(angle).toFloat(), circleY + inner * sin(angle).toFloat(),
                circleX + outer * cos(angle).toFloat(), circleY + outer * sin(angle).toFloat(), ringPaint)
        }
        ringPaint.alpha = 255
        directionPaint.textSize = 10 * density
        val baseline = -(directionPaint.ascent() + directionPaint.descent()) / 2
        listOf("N", "E", "S", "W").forEachIndexed { index, label ->
            val angle = Math.toRadians(index * 90.0 - 90.0)
            directionPaint.color = if (index == 0) mRockerColor else secondaryColor
            canvas.drawText(label, circleX + circleRadius * 0.64f * cos(angle).toFloat(),
                circleY + circleRadius * 0.64f * sin(angle).toFloat() + baseline, directionPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null || !isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                returnAnimator?.cancel()
                listener?.onStarted()

                val moveX = event.x
                val moveY = event.y

                val (mRockerPosition, w) = getRockerPositionPoint(mCenterPoint, Point(moveX.toInt(), moveY.toInt()), mAreaRadius, mRockerInnerCircleRadius)
                this.mRockerPosition = mRockerPosition
                moveRocker(mRockerPosition.x, mRockerPosition.y)

                if (!w.first && isLocked.get() && lockRunnable == null) {
                    isLocked.set(false)
                    listener?.onLockChanged(false)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isLocked.get()) {
                    val moveX = event.x
                    val moveY = event.y
                    val (mRockerPosition, w) = getRockerPositionPoint(
                        mCenterPoint,
                        Point(moveX.toInt(), moveY.toInt()),
                        mAreaRadius,
                        mRockerInnerCircleRadius
                    )
                    this.mRockerPosition = mRockerPosition
                    moveRocker(mRockerPosition.x, mRockerPosition.y)

                    // 自动时不锁定
                    if (w.first && lockRunnable == null && !isAuto.get()) {
                        lockRunnable = Runnable {
                            isLocked.set(true)
                            listener?.onLockChanged(true)
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            Toast.makeText(context, "方向锁定", Toast.LENGTH_SHORT).show()
                            lockRunnable = null
                            invalidate()
                        }
                        handler.postDelayed(lockRunnable!!, 3000)
                    }

                    if (!w.first && lockRunnable != null) {
                        handler.removeCallbacks(lockRunnable!!)
                        lockRunnable = null
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelPendingLock()
                if (!isLocked.get()) {
                    listener?.onFinished()
                    animateReturn()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingLock()
                isLocked.set(false)
                listener?.onLockChanged(false)
                listener?.onFinished()
                animateReturn()
            }
        }
        return true
    }

    private fun moveRocker(x: Int, y: Int) {
        mRockerPosition[x] = y
        invalidate()
    }

    private fun drawLock(canvas: Canvas) {
        val rockerRadius = mRockerInnerCircleRadius
        val lockSize = rockerRadius * 0.6f
        val rockerX = mRockerPosition.x.toFloat()
        val rockerY = mRockerPosition.y.toFloat()
        val rectF = RectF(rockerX - lockSize, rockerY - lockSize, rockerX + lockSize, rockerY + lockSize)
        canvas.drawBitmap(lockBitmap, null, rectF, lockPaint)
    }

    private fun getMySize(measureSpec: Int): Int {
        val result: Int
        val specMode = MeasureSpec.getMode(measureSpec)
        val defaultSize = (DEFAULT_SIZE * density).toInt()
        val specSize = MeasureSpec.getSize(measureSpec)
        result = when (specMode) {
            MeasureSpec.EXACTLY -> specSize
            MeasureSpec.AT_MOST -> defaultSize.coerceAtMost(specSize)
            else -> defaultSize
        }
        return result
    }

    private fun getRockerPositionPoint(
        centerPoint: Point,
        touchPoint: Point,
        regionRadius: Float,
        rockerRadius: Float
    ): Pair<Point, Pair<Boolean, Double>> {
        val lenX = (touchPoint.x - centerPoint.x).toFloat()
        val lenY = (touchPoint.y - centerPoint.y).toFloat()
        val lenXY = sqrt((lenX * lenX + lenY * lenY).toDouble()).toFloat()
        val radian = atan2(lenY.toDouble(), lenX.toDouble())
        val motion = JoystickInput.evaluate(lenX.toDouble(), lenY.toDouble(), (regionRadius - rockerRadius).coerceAtLeast(1f).toDouble())
        val angle = motion.bearing
        listener?.onStrength(motion.strength)
        if (lenXY + rockerRadius <= regionRadius) {
            listener?.onAngle(angle)
            return touchPoint to (false to angle)
        } else {
            val showPointX = (centerPoint.x + (regionRadius - rockerRadius) * cos(radian)).toInt()
            val showPointY = (centerPoint.y + (regionRadius - rockerRadius) * sin(radian)).toInt()
            listener?.onAngle(angle)
            return Point(showPointX, showPointY) to (true to angle)
        }
    }

    fun reset() {
        returnAnimator?.cancel()
        // joystick back to center
        lockRunnable?.let { handler.removeCallbacks(it) }
        lockRunnable = null
        mRockerPosition = Point()
        isLocked.set(false)
        listener?.onLockChanged(false)
        invalidate()
    }

    private fun cancelPendingLock() {
        lockRunnable?.let { handler.removeCallbacks(it) }
        lockRunnable = null
    }
    private fun animateReturn() {
        returnAnimator?.cancel()
        val x = mRockerPosition.x; val y = mRockerPosition.y
        returnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                moveRocker((x + (mCenterPoint.x - x) * f).toInt(), (y + (mCenterPoint.y - y) * f).toInt())
            }
            start()
        }
    }
    override fun onDetachedFromWindow() {
        reset()
        listener?.onFinished()
        super.onDetachedFromWindow()
    }

    fun auto(enable: Boolean) {
        isAuto.set(enable)
        invalidate()
    }

    companion object {
        const val DEFAULT_SIZE: Int = 144
        const val DEFAULT_PADDING_SIZE: Int = 12

        interface OnMoveListener {
            fun onStarted() {}

            fun onAngle(angle: Double)
            fun onStrength(strength: Double) {}

            fun onLockChanged(isLocked: Boolean)

            fun onFinished() {}
        }
    }
}
