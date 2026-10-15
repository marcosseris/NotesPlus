package com.notesplus.app.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin geometry helpers shared by the renderer, the eraser and the PDF exporter.
 * Kept free of android.* so the maths can be unit tested on the JVM.
 */
object InkGeometry {

    /** Minimum/maximum width multipliers applied to pen strokes by pressure. */
    const val PEN_MIN_FACTOR = 0.35f
    const val PEN_MAX_FACTOR = 1.35f

    fun penWidthFor(baseWidth: Float, pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        // Slight ease-in so light touches stay thin and firm strokes get bold.
        val eased = p * p * (3f - 2f * p)
        return baseWidth * (PEN_MIN_FACTOR + (PEN_MAX_FACTOR - PEN_MIN_FACTOR) * eased)
    }

    /**
     * Resamples a raw (x,y,p) polyline into a smooth Catmull-Rom curve. Returns a
     * new flat array of triples. Short strokes (dots) are returned unchanged.
     */
    fun smooth(pts: FloatArray, subdivisions: Int = 4): FloatArray {
        val n = pts.size / 3
        if (n < 3) return pts
        val out = ArrayList<Float>(n * subdivisions * 3)
        fun px(i: Int) = pts[i.coerceIn(0, n - 1) * 3]
        fun py(i: Int) = pts[i.coerceIn(0, n - 1) * 3 + 1]
        fun pp(i: Int) = pts[i.coerceIn(0, n - 1) * 3 + 2]
        out.add(px(0)); out.add(py(0)); out.add(pp(0))
        for (i in 0 until n - 1) {
            val x0 = px(i - 1); val y0 = py(i - 1)
            val x1 = px(i); val y1 = py(i)
            val x2 = px(i + 1); val y2 = py(i + 1)
            val x3 = px(i + 2); val y3 = py(i + 2)
            val p1 = pp(i); val p2 = pp(i + 1)
            val segLen = hypot(x2 - x1, y2 - y1)
            val steps = if (segLen < 1.5f) 1 else subdivisions
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val t2 = t * t; val t3 = t2 * t
                val x = 0.5f * ((2 * x1) + (-x0 + x2) * t + (2 * x0 - 5 * x1 + 4 * x2 - x3) * t2 + (-x0 + 3 * x1 - 3 * x2 + x3) * t3)
                val y = 0.5f * ((2 * y1) + (-y0 + y2) * t + (2 * y0 - 5 * y1 + 4 * y2 - y3) * t2 + (-y0 + 3 * y1 - 3 * y2 + y3) * t3)
                val p = p1 + (p2 - p1) * t
                out.add(x); out.add(y); out.add(p)
            }
        }
        return out.toFloatArray()
    }

    /**
     * Builds a closed outline polygon for a variable-width stroke. The returned flat
     * array of (x,y) pairs goes along the left side and back along the right side;
     * round caps are added at both ends. Suitable for filling with non-zero winding.
     */
    fun outline(pts: FloatArray, baseWidth: Float, capSegments: Int = 8): FloatArray {
        val n = pts.size / 3
        if (n == 0) return FloatArray(0)
        if (n == 1 || isDegenerate(pts)) {
            // A dot: emit a circle.
            val r = penWidthFor(baseWidth, pts[2]) / 2f
            return circle(pts[0], pts[1], max(r, baseWidth * 0.3f), capSegments * 2)
        }
        val left = FloatArray(n * 2)
        val right = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]
            // Direction from neighbours (central difference where possible).
            val ia = max(i - 1, 0); val ib = min(i + 1, n - 1)
            var dx = pts[ib * 3] - pts[ia * 3]
            var dy = pts[ib * 3 + 1] - pts[ia * 3 + 1]
            var len = hypot(dx, dy)
            if (len < 1e-4f) { dx = 1f; dy = 0f; len = 1f }
            val nx = -dy / len; val ny = dx / len
            val hw = penWidthFor(baseWidth, pts[i * 3 + 2]) / 2f
            left[i * 2] = x + nx * hw; left[i * 2 + 1] = y + ny * hw
            right[i * 2] = x - nx * hw; right[i * 2 + 1] = y - ny * hw
        }
        val out = ArrayList<Float>((n * 4 + capSegments * 4) * 2)
        // Left side forward.
        for (i in 0 until n) { out.add(left[i * 2]); out.add(left[i * 2 + 1]) }
        // End cap: half circle beyond the last point, in the direction of travel.
        var k = n - 2
        while (k > 0 && hypot(pts[(n - 1) * 3] - pts[k * 3], pts[(n - 1) * 3 + 1] - pts[k * 3 + 1]) < 0.01f) k--
        addCap(out, pts[(n - 1) * 3], pts[(n - 1) * 3 + 1], left[(n - 1) * 2], left[(n - 1) * 2 + 1],
            pts[(n - 1) * 3] - pts[k * 3], pts[(n - 1) * 3 + 1] - pts[k * 3 + 1], capSegments)
        // Right side backward.
        for (i in n - 1 downTo 0) { out.add(right[i * 2]); out.add(right[i * 2 + 1]) }
        // Start cap: half circle behind the first point, opposite to the direction of travel.
        var j = 1
        while (j < n - 1 && hypot(pts[j * 3] - pts[0], pts[j * 3 + 1] - pts[1]) < 0.01f) j++
        addCap(out, pts[0], pts[1], right[0], right[1], pts[0] - pts[j * 3], pts[1] - pts[j * 3 + 1], capSegments)
        return out.toFloatArray()
    }

    private fun isDegenerate(pts: FloatArray): Boolean {
        val n = pts.size / 3
        val x0 = pts[0]; val y0 = pts[1]
        for (i in 1 until n) if (abs(pts[i * 3] - x0) > 0.05f || abs(pts[i * 3 + 1] - y0) > 0.05f) return false
        return true
    }

    /**
     * Appends a half-circle cap around (cx,cy) starting at (fromX,fromY) and sweeping
     * through the direction (outDx,outDy), which must point away from the stroke body.
     */
    private fun addCap(out: MutableList<Float>, cx: Float, cy: Float, fromX: Float, fromY: Float, outDx: Float, outDy: Float, segments: Int) {
        val r = hypot(fromX - cx, fromY - cy)
        if (r < 1e-4f) return
        val a0 = Math.atan2((fromY - cy).toDouble(), (fromX - cx).toDouble())
        val ao = Math.atan2(outDy.toDouble(), outDx.toDouble())
        var d = ao - a0
        while (d <= -Math.PI) d += 2 * Math.PI
        while (d > Math.PI) d -= 2 * Math.PI
        val sign = if (d >= 0) 1.0 else -1.0
        for (s in 1 until segments) {
            val a = a0 + sign * Math.PI * s / segments
            out.add((cx + r * Math.cos(a)).toFloat()); out.add((cy + r * Math.sin(a)).toFloat())
        }
    }

    fun circle(cx: Float, cy: Float, r: Float, segments: Int): FloatArray {
        val out = FloatArray(segments * 2)
        for (i in 0 until segments) {
            val a = 2 * Math.PI * i / segments
            out[i * 2] = (cx + r * Math.cos(a)).toFloat()
            out[i * 2 + 1] = (cy + r * Math.sin(a)).toFloat()
        }
        return out
    }

    /** Squared distance from point (px,py) to segment (ax,ay)-(bx,by). */
    fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val wx = px - ax; val wy = py - ay
        val c1 = vx * wx + vy * wy
        if (c1 <= 0f) return wx * wx + wy * wy
        val c2 = vx * vx + vy * vy
        if (c2 <= c1) { val dx = px - bx; val dy = py - by; return dx * dx + dy * dy }
        val b = c1 / c2
        val qx = ax + b * vx; val qy = ay + b * vy
        val dx = px - qx; val dy = py - qy
        return dx * dx + dy * dy
    }

    /** True when an eraser circle of [radius] centred at (ex,ey) touches [stroke]. */
    fun hits(stroke: Stroke, ex: Float, ey: Float, radius: Float): Boolean {
        val b = stroke.bounds()
        if (ex < b[0] - radius || ex > b[2] + radius || ey < b[1] - radius || ey > b[3] + radius) return false
        val n = stroke.pointCount
        if (n == 1) {
            val d = hypot(stroke.x(0) - ex, stroke.y(0) - ey)
            return d <= radius + stroke.width
        }
        for (i in 0 until n - 1) {
            val hw = if (stroke.tool == ToolType.PEN) penWidthFor(stroke.width, max(stroke.p(i), stroke.p(i + 1))) / 2f else stroke.width / 2f
            val reach = radius + hw
            val d2 = distSqToSegment(ex, ey, stroke.x(i), stroke.y(i), stroke.x(i + 1), stroke.y(i + 1))
            if (d2 <= reach * reach) return true
        }
        return false
    }

    /** True when the segment (ax,ay)-(bx,by) swept by an eraser of [radius] touches [stroke]. */
    fun hitsSegment(stroke: Stroke, ax: Float, ay: Float, bx: Float, by: Float, radius: Float): Boolean {
        val len = hypot(bx - ax, by - ay)
        val steps = max(1, (len / (radius * 0.75f)).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            if (hits(stroke, ax + (bx - ax) * t, ay + (by - ay) * t, radius)) return true
        }
        return false
    }

    fun length(pts: FloatArray): Float {
        var l = 0f
        val n = pts.size / 3
        for (i in 1 until n) l += hypot(pts[i * 3] - pts[(i - 1) * 3], pts[i * 3 + 1] - pts[(i - 1) * 3 + 1])
        return l
    }

    @Suppress("unused")
    private fun norm(x: Float, y: Float) = sqrt(x * x + y * y)
}
