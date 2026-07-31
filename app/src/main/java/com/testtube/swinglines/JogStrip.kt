package com.testtube.swinglines

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Relative jog wheel for frame-accurate scrubbing (the Coach's Eye behaviour).
 *
 * NOT an absolute control: dragging moves the clip by frames proportional to
 * finger travel since the last event. Gearing is fixed at 6dp per frame, with
 * a float accumulator so slow drags never lose movement. No inertia - lift
 * your finger and the frame stays put. Ticks scroll under a fixed centre
 * marker so the motion is visible.
 */
class JogStrip(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Called with +1 or -1 for each whole frame of travel. */
    var onFrameStep: ((Int) -> Unit)? = null

    /** Called on first touch, so the caller can pause playback. */
    var onGrabbed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val gearPx = dp(6f) // 6dp of travel = 1 frame
    private var lastX = 0f
    private var accum = 0f
    private var tickOffset = 0f

    private val minorTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.5f)
        color = Color.argb(120, 255, 255, 255)
    }
    private val majorTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2.5f)
        color = Color.argb(200, 255, 255, 255)
    }
    private val centreMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(3f)
        color = Color.rgb(46, 160, 67)
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(140, 11, 15, 13)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRoundRect(0f, 0f, w, h, dp(10f), dp(10f), bgPaint)

        // ticks every 6dp (one per frame), heavier every 10th, scrolling with drag
        val major = gearPx * 10f
        var x = (tickOffset % major + major) % major - major
        var idx = 0
        while (x < w + major) {
            val isMajor = ((x - tickOffset) / gearPx).toInt() % 10 == 0
            val tx = x
            if (tx >= 0f && tx <= w) {
                val paint = if (isMajor) majorTick else minorTick
                val inset = if (isMajor) dp(8f) else dp(13f)
                canvas.drawLine(tx, inset, tx, h - inset, paint)
            }
            x += gearPx
            idx++
            if (idx > 400) break // safety on absurd widths
        }

        // fixed centre marker
        canvas.drawLine(w / 2f, dp(4f), w / 2f, h - dp(4f), centreMark)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                accum = 0f
                onGrabbed?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                lastX = event.x
                accum += dx
                tickOffset += dx
                // emit whole frames, keep the remainder (spec: accumulate, don't round)
                while (accum >= gearPx) {
                    accum -= gearPx
                    onFrameStep?.invoke(1) // drag right = forward
                }
                while (accum <= -gearPx) {
                    accum += gearPx
                    onFrameStep?.invoke(-1)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
