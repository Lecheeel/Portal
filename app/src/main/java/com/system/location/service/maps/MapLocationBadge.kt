package com.system.location.service.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import com.system.location.service.R

/** The marker anchor is the centre of the dot, below its persistent caption. */
internal class MapLocationBadge(context: Context, private val original: Boolean, private val caption: String) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blue = android.graphics.Color.rgb(37, 99, 235)
    private val muted = ContextCompat.getColor(context, R.color.text_secondary)
    private val surface = ContextCompat.getColor(context, R.color.surface)
    private val dashed = DashPathEffect(floatArrayOf(3 * density, 3 * density), 0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((116 * density).toInt(), (64 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = 44 * density
        val color = if (original) muted else blue
        paint.style = Paint.Style.FILL
        paint.color = surface
        paint.alpha = if (original) 210 else 245
        canvas.drawRoundRect(2 * density, 0f, width - 2 * density, 25 * density, 12 * density, 12 * density, paint)
        paint.alpha = 255
        paint.color = color
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = minOf(12 * resources.displayMetrics.scaledDensity, 14 * density)
        val baseline = 12.5f * density - (paint.ascent() + paint.descent()) / 2
        canvas.drawText(caption, cx, baseline, paint)
        paint.alpha = if (original) 16 else 30
        canvas.drawCircle(cx, cy, 17 * density, paint)
        paint.alpha = if (original) 150 else 255
        if (original) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.pathEffect = dashed
            canvas.drawCircle(cx, cy, 12 * density, paint)
            paint.pathEffect = null
            paint.strokeWidth = density
            canvas.drawCircle(cx, cy, 4 * density, paint)
        } else {
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(cx, cy, 10 * density, paint)
            paint.color = blue
            canvas.drawCircle(cx, cy, 7 * density, paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }
}
