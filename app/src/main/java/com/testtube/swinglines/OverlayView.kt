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
 *
 * Editing model:
 *  - drag a white endpoint dot to reshape a line / resize a circle
 *  - drag anywhere along a shape's body to move the WHOLE shape
 *  - touch empty space to draw a new shape with the current tool
 *
 * All sizes are density-scaled (dp), never raw pixels - raw px made the
 * handles nearly untouchable on high-density phones.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Shape(val type: String, val color: Int, val pts: MutableList<PointF>)

    var tool: String = "line"
    var drawColor: Int = Color.RED
    var showGrid: Boolean = false
        set(value) { field = value; invalidate() }

    val shapes = mutableListOf<Shape>()
    private var drawing: Shape? = null

    // dragging state: dragIdx >= 0 moves one point, WHOLE_SHAPE moves everything
    private var dragShape: Shape? = null
    private var dragIdx: Int = 0
    private var lastNx = 0f
    private var lastNy = 0f

    // hold-to-grab: touching an existing shape no longer grabs it instantly.
    // Hold still for a beat (haptic plus a highlight confirms) to grab it; drag
    // straight away to draw a NEW shape even when starting on top of one.
    //
    // This applies to ENDPOINTS as well as bodies. Golf lines radiate from the
    // ball, so their endpoints pile up in one spot: with instant endpoint grabs
    // every new line started near the ball reshaped an old line instead.
    private var pendingShape: Shape? = null
    private var pendingIdx: Int = WHOLE_SHAPE
    private var downNx = 0f
    private var downNy = 0f
    private val holdRunnable = Runnable {
        val s = pendingShape
        if (s != null) {
            pendingShape = null
            dragShape = s
            dragIdx = pendingIdx
            lastNx = downNx
            lastNy = downNy
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate() // show the grab highlight
        }
    }

    var onShapesChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.75f) // halved at Rich's request
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val anglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
    }
    /** halo drawn under a grabbed shape so it is obvious what is being moved */
    private val grabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(7f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(120, 255, 255, 255)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(110, 255, 255, 255)
    }

    companion object {
        private const val WHOLE_SHAPE = -1
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
        val grabbed = dragShape
        for (s in all) {
            // grabbed shape gets a halo underneath first, so the coach can see
            // what he has hold of. Haptic alone left it ambiguous.
            if (s === grabbed) strokeShape(canvas, s, w, h, grabPaint)
            strokePaint.color = s.color
            strokeShape(canvas, s, w, h, strokePaint)
        }
        // end dots removed at Rich's request - the endpoint grab zones still
        // work, there is just nothing drawn there any more

        // live angle readout while a straight line is being drawn or adjusted
        val active = drawing ?: dragShape
        if (active != null && active.type == "line" && active.pts.size == 2) {
            val x1 = active.pts[0].x * w
            val y1 = active.pts[0].y * h
            val x2 = active.pts[1].x * w
            val y2 = active.pts[1].y * h
            if (hypot(x2 - x1, y2 - y1) > dp(20f)) {
                val deg = Math.toDegrees(
                    kotlin.math.atan2(abs(y2 - y1).toDouble(), abs(x2 - x1).toDouble())
                ).toInt()
                canvas.drawText("$deg°", (x1 + x2) / 2f, (y1 + y2) / 2f - dp(12f), anglePaint)
            }
        }
    }

    /** Draw one shape's outline with the given paint. */
    private fun strokeShape(canvas: Canvas, s: Shape, w: Float, h: Float, paint: Paint) {
        if (s.type == "circle") {
            if (s.pts.size == 2) {
                val cx = s.pts[0].x * w
                val cy = s.pts[0].y * h
                val r = hypot((s.pts[1].x - s.pts[0].x) * w, (s.pts[1].y - s.pts[0].y) * h)
                if (r > dp(2f)) canvas.drawCircle(cx, cy, r, paint)
            }
            return
        }
        for (i in 1 until s.pts.size) {
            canvas.drawLine(
                s.pts[i - 1].x * w, s.pts[i - 1].y * h,
                s.pts[i].x * w, s.pts[i].y * h, paint
            )
        }
    }

    /** Distance from point to a segment, in pixels. */
    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0f) 0f else (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    /** Is this touch (pixels) on the shape's body? */
    private fun hitsBody(s: Shape, px: Float, py: Float, w: Float, h: Float, tol: Float): Boolean {
        if (s.type == "circle" && s.pts.size == 2) {
            val cx = s.pts[0].x * w
            val cy = s.pts[0].y * h
            val r = hypot((s.pts[1].x - s.pts[0].x) * w, (s.pts[1].y - s.pts[0].y) * h)
            return abs(hypot(px - cx, py - cy) - r) <= tol
        }
        for (i in 1 until s.pts.size) {
            if (distToSegment(
                    px, py,
                    s.pts[i - 1].x * w, s.pts[i - 1].y * h,
                    s.pts[i].x * w, s.pts[i].y * h
                ) <= tol
            ) return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val nx = (event.x / w).coerceIn(0f, 1f)
        val ny = (event.y / h).coerceIn(0f, 1f)
        val px = event.x
        val py = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val grabR = dp(24f)      // endpoint grab radius - a proper fingertip target
                val bodyTol = dp(16f)    // how close to the shape body counts as touching it
                downNx = nx
                downNy = ny
                // 1) endpoint handles - arm the hold-to-grab, same as bodies below.
                // Nearest endpoint wins, so clustered ends round the ball pick the
                // one actually under the finger rather than the topmost shape.
                var bestShape: Shape? = null
                var bestIdx = WHOLE_SHAPE
                var bestDist = Float.MAX_VALUE
                for (s in shapes.reversed()) {
                    if (s.type != "line" && s.type != "circle") continue
                    for ((i, p) in s.pts.withIndex()) {
                        val d = hypot(p.x * w - px, p.y * h - py)
                        if (d <= grabR && d < bestDist) {
                            bestDist = d
                            bestShape = s
                            bestIdx = i
                        }
                    }
                }
                if (bestShape != null) {
                    pendingShape = bestShape
                    pendingIdx = bestIdx
                    postDelayed(holdRunnable, 260)
                    return true
                }
                // 2) shape body - arm the hold-to-move; an immediate drag draws instead
                for (s in shapes.reversed()) {
                    if (hitsBody(s, px, py, w, h, bodyTol)) {
                        pendingShape = s
                        pendingIdx = WHOLE_SHAPE
                        postDelayed(holdRunnable, 260)
                        return true
                    }
                }
                // 3) empty space - start a new shape straight away
                drawing = if (tool == "line" || tool == "circle") {
                    Shape(tool, drawColor, mutableListOf(PointF(nx, ny), PointF(nx, ny)))
                } else {
                    Shape("draw", drawColor, mutableListOf(PointF(nx, ny)))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // finger moved before the hold fired: cancel the grab, draw a new
                // shape starting from the original touch point
                if (pendingShape != null) {
                    val slop = dp(8f)
                    if (hypot((nx - downNx) * w, (ny - downNy) * h) > slop) {
                        removeCallbacks(holdRunnable)
                        pendingShape = null
                        drawing = if (tool == "line" || tool == "circle") {
                            Shape(tool, drawColor, mutableListOf(PointF(downNx, downNy), PointF(nx, ny)))
                        } else {
                            Shape("draw", drawColor, mutableListOf(PointF(downNx, downNy), PointF(nx, ny)))
                        }
                        invalidate()
                        return true
                    }
                    return true
                }
                val ds = dragShape
                if (ds != null) {
                    if (dragIdx == WHOLE_SHAPE || (ds.type == "circle" && dragIdx == 0)) {
                        val dx = nx - lastNx
                        val dy = ny - lastNy
                        for (i in ds.pts.indices) {
                            ds.pts[i] = PointF(ds.pts[i].x + dx, ds.pts[i].y + dy)
                        }
                    } else {
                        ds.pts[dragIdx] = PointF(nx, ny)
                    }
                    lastNx = nx
                    lastNy = ny
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
                removeCallbacks(holdRunnable)
                pendingShape = null
                if (dragShape != null) {
                    dragShape = null
                    onShapesChanged?.invoke()
                    invalidate() // clear the grab highlight
                    performClick()
                    return true
                }
                val d = drawing ?: return true
                drawing = null
                val first = d.pts.first()
                val last = d.pts.last()
                val moved = hypot((first.x - last.x) * w, (first.y - last.y) * h) > dp(8f)
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
