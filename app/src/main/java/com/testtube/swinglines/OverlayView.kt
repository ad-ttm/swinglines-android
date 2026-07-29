package com.testtube.swinglines

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Transparent drawing layer over the camera preview.
 * Shapes are stored in NORMALISED coordinates (0..1) so they survive
 * rotation, resize and can be saved/loaded as setups.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Shape(val type: String, val color: Int, val pts: MutableList<PointF>)

    var tool: String = "line"
    var drawColor: Int = Color.RED
    var showGrid: Boolean = false
        set(value) { field = value; invalidate() }

    val shapes = mutableListOf<Shape>()
    private var drawing: Shape? = null
    private var dragShape: Shape? = null
    private var dragIdx: Int = 0

    var onShapesChanged: (() -> Unit)? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 255, 255, 255)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(110, 255, 255, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (showGrid) {
            for (f in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                canvas.drawLine(f * w, 0f, f * w, h, gridPaint)
                canvas.drawLine(0f, f * h, w, f * h, gridPaint)
            }
        }

        val all = ArrayList<Shape>(shapes)
        drawing?.let { all.add(it) }
        for (s in all) {
            strokePaint.color = s.color
            when (s.type) {
                "circle" -> {
                    if (s.pts.size == 2) {
                        val cx = s.pts[0].x * w
                        val cy = s.pts[0].y * h
                        val r = hypot((s.pts[1].x - s.pts[0].x) * w, (s.pts[1].y - s.pts[0].y) * h)
                        if (r > 2f) canvas.drawCircle(cx, cy, r, strokePaint)
                    }
                }
                else -> {
                    for (i in 1 until s.pts.size) {
                        canvas.drawLine(
                            s.pts[i - 1].x * w, s.pts[i - 1].y * h,
                            s.pts[i].x * w, s.pts[i].y * h, strokePaint
                        )
                    }
                }
            }
        }
        // endpoint handles for editable shapes
        for (s in shapes) {
            if (s.type != "line" && s.type != "circle") continue
            for ((i, p) in s.pts.withIndex()) {
                if (s.type == "circle" && i > 0) continue
                canvas.drawCircle(p.x * w, p.y * h, 12f, handlePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val nx = (event.x / w).coerceIn(0f, 1f)
        val ny = (event.y / h).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // grab an existing handle first (48px touch target)
                for (s in shapes.reversed()) {
                    if (s.type != "line" && s.type != "circle") continue
                    for ((i, p) in s.pts.withIndex()) {
                        if (hypot((p.x - nx) * w, (p.y - ny) * h) <= 48f) {
                            dragShape = s
                            dragIdx = i
                            return true
                        }
                    }
                }
                drawing = if (tool == "line" || tool == "circle") {
                    Shape(tool, drawColor, mutableListOf(PointF(nx, ny), PointF(nx, ny)))
                } else {
                    Shape("draw", drawColor, mutableListOf(PointF(nx, ny)))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val ds = dragShape
                if (ds != null) {
                    if (ds.type == "circle" && dragIdx == 0) {
                        val dx = ds.pts[1].x - ds.pts[0].x
                        val dy = ds.pts[1].y - ds.pts[0].y
                        ds.pts[0] = PointF(nx, ny)
                        ds.pts[1] = PointF(nx + dx, ny + dy)
                    } else {
                        ds.pts[dragIdx] = PointF(nx, ny)
                    }
                    invalidate()
                    return true
                }
                val d = drawing ?: return true
                if (d.type == "draw") {
                    d.pts.add(PointF(nx, ny))
                } else {
                    d.pts[1] = PointF(nx, ny)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragShape != null) {
                    dragShape = null
                    onShapesChanged?.invoke()
                    performClick()
                    return true
                }
                val d = drawing ?: return true
                drawing = null
                val first = d.pts.first()
                val last = d.pts.last()
                val moved = hypot((first.x - last.x) * w, (first.y - last.y) * h) > 12f
                val keep = if (d.type == "draw") d.pts.size > 2 else moved
                if (keep) shapes.add(d)
                onShapesChanged?.invoke()
                invalidate()
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

    fun undo() {
        if (shapes.isNotEmpty()) {
            shapes.removeAt(shapes.size - 1)
            onShapesChanged?.invoke()
            invalidate()
        }
    }

    fun clearAll() {
        shapes.clear()
        onShapesChanged?.invoke()
        invalidate()
    }

    fun serialize(): String {
        val arr = JSONArray()
        for (s in shapes) {
            val o = JSONObject()
            o.put("type", s.type)
            o.put("color", s.color)
            val pts = JSONArray()
            for (p in s.pts) {
                val pt = JSONObject()
                pt.put("x", p.x.toDouble())
                pt.put("y", p.y.toDouble())
                pts.put(pt)
            }
            o.put("pts", pts)
            arr.put(o)
        }
        return arr.toString()
    }

    fun loadFrom(json: String) {
        try {
            val arr = JSONArray(json)
            shapes.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pts = mutableListOf<PointF>()
                val pa = o.getJSONArray("pts")
                for (j in 0 until pa.length()) {
                    val pt = pa.getJSONObject(j)
                    pts.add(PointF(pt.getDouble("x").toFloat(), pt.getDouble("y").toFloat()))
                }
                shapes.add(Shape(o.getString("type"), o.getInt("color"), pts))
            }
            invalidate()
        } catch (_: Exception) {
            // corrupt setup - ignore rather than crash
        }
    }
}
