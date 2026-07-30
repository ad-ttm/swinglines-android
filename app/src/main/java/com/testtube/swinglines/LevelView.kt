package com.testtube.swinglines

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small spirit level bar. Feed it the device roll in degrees (0 = level);
 * the bubble slides towards the low side and everything turns green when the
 * phone is within one degree of level. Works for portrait or landscape
 * mounting because the caller folds the angle to the nearest upright.
 */
class LevelView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var rollDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(150, 255, 255, 255)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cy = h / 2f
        val level = abs(rollDegrees) <= 1f

        // track with centre notch
        canvas.drawLine(dp(4f), cy, w - dp(4f), cy, trackPaint)
        canvas.drawLine(w / 2f, cy - dp(6f), w / 2f, cy + dp(6f), trackPaint)

        // bubble: clamp to +/-10 degrees across the bar
        val clamped = rollDegrees.coerceIn(-10f, 10f)
        val bx = w / 2f + (clamped / 10f) * (w / 2f - dp(12f))
        bubblePaint.color = if (level) Color.rgb(46, 160, 67) else Color.rgb(255, 214, 10)
        canvas.drawCircle(bx, cy, dp(7f), bubblePaint)

        // readout
        textPaint.color = if (level) Color.rgb(126, 231, 135) else Color.argb(220, 255, 255, 255)
        canvas.drawText("${abs(rollDegrees).roundToInt()}°", w / 2f, h - dp(2f), textPaint)
    }
}
