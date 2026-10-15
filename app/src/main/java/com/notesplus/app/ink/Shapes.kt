package com.notesplus.app.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Shapes the explicit shape tool can draw by dragging. */
enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE, TRIANGLE }

/**
 * Builds the polylines (flat x,y pairs) for a shape dragged from (x0,y0) to (x1,y1).
 * Multi-segment shapes return one polyline per edge so corners stay crisp and each
 * edge can be erased on its own.
 */
object ShapeBuilder {
    fun build(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float, constrain: Boolean = false): List<FloatArray> {
        var ex = x1; var ey = y1
        if (constrain) {
            when (kind) {
                ShapeKind.LINE, ShapeKind.ARROW -> {
                    // Snap the angle to multiples of 45 degrees.
                    val len = hypot(ex - x0, ey - y0)
                    val a = round(atan2((ey - y0).toDouble(), (ex - x0).toDouble()) / (PI / 4)) * (PI / 4)
                    ex = x0 + (len * cos(a)).toFloat(); ey = y0 + (len * sin(a)).toFloat()
                }
                else -> {
                    // Square / circle / equilateral-ish: equal extents.
                    val s = max(abs(ex - x0), abs(ey - y0))
                    ex = x0 + if (ex >= x0) s else -s
                    ey = y0 + if (ey >= y0) s else -s
                }
            }
        }
        return when (kind) {
            ShapeKind.LINE -> listOf(floatArrayOf(x0, y0, ex, ey))
            ShapeKind.ARROW -> arrow(x0, y0, ex, ey)
            ShapeKind.RECTANGLE -> rectangle(min(x0, ex), min(y0, ey), max(x0, ex), max(y0, ey))
            ShapeKind.ELLIPSE -> listOf(ellipse((x0 + ex) / 2f, (y0 + ey) / 2f, abs(ex - x0) / 2f, abs(ey - y0) / 2f))
            ShapeKind.TRIANGLE -> {
                val l = min(x0, ex); val r = max(x0, ex); val t = min(y0, ey); val b = max(y0, ey)
                polygon(floatArrayOf((l + r) / 2f, t, r, b, l, b))
            }
        }
    }

    fun arrow(x0: Float, y0: Float, x1: Float, y1: Float): List<FloatArray> {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 0.5f) return listOf(floatArrayOf(x0, y0, x1, y1))
        val head = (len * 0.22f).coerceIn(6f, 26f)
        val a = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val spread = Math.toRadians(27.0)
        val hx1 = x1 - (head * cos(a - spread)).toFloat(); val hy1 = y1 - (head * sin(a - spread)).toFloat()
        val hx2 = x1 - (head * cos(a + spread)).toFloat(); val hy2 = y1 - (head * sin(a + spread)).toFloat()
        return listOf(floatArrayOf(x0, y0, x1, y1), floatArrayOf(hx1, hy1, x1, y1), floatArrayOf(hx2, hy2, x1, y1))
    }

    fun rectangle(l: Float, t: Float, r: Float, b: Float): List<FloatArray> = polygon(floatArrayOf(l, t, r, t, r, b, l, b))

    /** Closed polygon given as x,y pairs; returns one polyline per edge. */
    fun polygon(vertices: FloatArray): List<FloatArray> {
        val n = vertices.size / 2
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            out += floatArrayOf(vertices[i * 2], vertices[i * 2 + 1], vertices[j * 2], vertices[j * 2 + 1])
        }
        return out
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 72): FloatArray {
        val out = FloatArray((segments + 1) * 2)
        for (i in 0..segments) {
            val a = 2 * PI * i / segments
            out[i * 2] = cx + (rx * cos(a)).toFloat()
            out[i * 2 + 1] = cy + (ry * sin(a)).toFloat()
        }
        return out
    }

    /** Converts an x,y polyline into stroke points with constant pressure. */
    fun toStrokePoints(xy: FloatArray, pressure: Float = 0.6f): FloatArray {
        val n = xy.size / 2
        val out = FloatArray(n * 3)
        for (i in 0 until n) { out[i * 3] = xy[i * 2]; out[i * 3 + 1] = xy[i * 2 + 1]; out[i * 3 + 2] = pressure }
        return out
    }
}

/**
 * Recognises what a hand-drawn stroke was meant to be. Used for "hold to snap": pause
 * the pen at the end of a stroke and it turns into a clean shape.
 */
object ShapeRecognizer {
    sealed class Result {
        data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Result()
        /** Open chain of straight segments (an L, a V, a zig-zag). */
        data class Polyline(val xy: FloatArray) : Result()
        data class Rectangle(val l: Float, val t: Float, val r: Float, val b: Float) : Result()
        data class Triangle(val xy: FloatArray) : Result()
        /** Closed polygon with 4+ corners that is not axis-aligned enough to be a rectangle. */
        data class Polygon(val xy: FloatArray) : Result()
        data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : Result()

        /** The polylines (x,y pairs) that draw this shape. */
        fun polylines(): List<FloatArray> = when (this) {
            is Line -> listOf(floatArrayOf(x0, y0, x1, y1))
            is Polyline -> (0 until xy.size / 2 - 1).map { i -> floatArrayOf(xy[i * 2], xy[i * 2 + 1], xy[i * 2 + 2], xy[i * 2 + 3]) }
            is Rectangle -> ShapeBuilder.rectangle(l, t, r, b)
            is Triangle -> ShapeBuilder.polygon(xy)
            is Polygon -> ShapeBuilder.polygon(xy)
            is Ellipse -> listOf(ShapeBuilder.ellipse(cx, cy, rx, ry))
        }
    }

    /** [pts] are (x,y,p) triples in page points. Returns null when nothing is recognised. */
    fun recognize(pts: FloatArray): Result? {
        val xy = resample(toXY(pts), 96) ?: return null
        val n = xy.size / 2
        val x0 = xy[0]; val y0 = xy[1]; val x1 = xy[(n - 1) * 2]; val y1 = xy[(n - 1) * 2 + 1]
        val bb = bounds(xy)
        val diag = hypot(bb[2] - bb[0], bb[3] - bb[1])
        if (diag < 6f) return null
        val pathLen = length(xy)
        val chord = hypot(x1 - x0, y1 - y0)

        // 1. Straight line: everything close to the chord and the path barely longer than it.
        if (chord > 0.6f * diag) {
            val maxDev = maxDeviation(xy, x0, y0, x1, y1)
            if (maxDev <= max(3f, 0.045f * chord) && pathLen <= chord * 1.12f) {
                return snapLineAngle(x0, y0, x1, y1)
            }
        }

        val closed = chord < 0.22f * max(pathLen, diag)
        val corners = simplify(xy, 0.055f * diag)
        val cornerCount = corners.size / 2

        if (closed) {
            // Ellipse: radius (normalised to the bounding box) stays near 1 all the way round.
            val cx = (bb[0] + bb[2]) / 2f; val cy = (bb[1] + bb[3]) / 2f
            val rx = max(1f, (bb[2] - bb[0]) / 2f); val ry = max(1f, (bb[3] - bb[1]) / 2f)
            var sum = 0.0; var sumSq = 0.0
            for (i in 0 until n) {
                val r = sqrt(sq((xy[i * 2] - cx) / rx) + sq((xy[i * 2 + 1] - cy) / ry))
                sum += r; sumSq += r * r
            }
            val mean = sum / n
            val sd = sqrt(max(0.0, sumSq / n - mean * mean))
            // A polygon drawn through its own corners also keeps r≈1 at the corners, so require
            // that the simplifier did not find a small number of strong corners.
            val polygonal = cornerCount in 4..5 && cornersAreSharp(corners)
            if (sd < 0.11 && !polygonal) return Result.Ellipse(cx, cy, rx, ry)

            // Closed polygon: drop the closing vertex if the simplifier kept both ends.
            val verts = dedupeClosed(corners, 0.1f * diag)
            val vc = verts.size / 2
            if (vc == 3) return Result.Triangle(verts)
            if (vc == 4) {
                // Rectangle when the corners sit near the bounding box corners.
                val tol = 0.12f * diag
                val nearBox = (0 until 4).all { i ->
                    val vx = verts[i * 2]; val vy = verts[i * 2 + 1]
                    (abs(vx - bb[0]) < tol || abs(vx - bb[2]) < tol) && (abs(vy - bb[1]) < tol || abs(vy - bb[3]) < tol)
                }
                return if (nearBox) Result.Rectangle(bb[0], bb[1], bb[2], bb[3]) else Result.Polygon(verts)
            }
            if (vc in 5..8) return Result.Polygon(verts)
            return null
        }

        // 2. Open chain of 2-4 straight segments.
        if (cornerCount in 3..5) {
            val chain = snapChain(corners)
            // Each segment must be reasonably straight; the simplifier guarantees that within epsilon.
            return Result.Polyline(chain)
        }
        return null
    }

    // ------------------------------------------------------------------ helpers

    private fun sq(v: Float) = (v * v).toDouble()

    private fun toXY(pts: FloatArray): FloatArray {
        val n = pts.size / 3
        val out = FloatArray(n * 2)
        for (i in 0 until n) { out[i * 2] = pts[i * 3]; out[i * 2 + 1] = pts[i * 3 + 1] }
        return out
    }

    fun bounds(xy: FloatArray): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until xy.size / 2) {
            l = min(l, xy[i * 2]); r = max(r, xy[i * 2]); t = min(t, xy[i * 2 + 1]); b = max(b, xy[i * 2 + 1])
        }
        return floatArrayOf(l, t, r, b)
    }

    fun length(xy: FloatArray): Float {
        var s = 0f
        for (i in 1 until xy.size / 2) s += hypot(xy[i * 2] - xy[i * 2 - 2], xy[i * 2 + 1] - xy[i * 2 - 1])
        return s
    }

    /** Resamples to [count] evenly spaced points; null if the input is degenerate. */
    fun resample(xy: FloatArray, count: Int): FloatArray? {
        val n = xy.size / 2
        if (n < 2) return null
        val total = length(xy)
        if (total < 1e-3f) return null
        val step = total / (count - 1)
        val out = ArrayList<Float>(count * 2)
        out.add(xy[0]); out.add(xy[1])
        var acc = 0f
        var px = xy[0]; var py = xy[1]
        var i = 1
        while (i < n && out.size < count * 2) {
            val qx = xy[i * 2]; val qy = xy[i * 2 + 1]
            val d = hypot(qx - px, qy - py)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val nx = px + t * (qx - px); val ny = py + t * (qy - py)
                out.add(nx); out.add(ny)
                px = nx; py = ny; acc = 0f
            } else {
                acc += d; px = qx; py = qy; i++
            }
        }
        while (out.size < count * 2) { out.add(xy[(n - 1) * 2]); out.add(xy[(n - 1) * 2 + 1]) }
        return out.toFloatArray()
    }

    private fun maxDeviation(xy: FloatArray, ax: Float, ay: Float, bx: Float, by: Float): Float {
        var m = 0f
        for (i in 0 until xy.size / 2) m = max(m, sqrt(InkGeometry.distSqToSegment(xy[i * 2], xy[i * 2 + 1], ax, ay, bx, by)))
        return m
    }

    /** Ramer–Douglas–Peucker simplification; returns the kept vertices as x,y pairs. */
    fun simplify(xy: FloatArray, epsilon: Float): FloatArray {
        val n = xy.size / 2
        if (n < 3) return xy
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0f; var idx = -1
            for (i in a + 1 until b) {
                val d = InkGeometry.distSqToSegment(xy[i * 2], xy[i * 2 + 1], xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > epsilon * epsilon) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b))
            }
        }
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) { out.add(xy[i * 2]); out.add(xy[i * 2 + 1]) }
        return out.toFloatArray()
    }

    private fun cornersAreSharp(corners: FloatArray): Boolean {
        val n = corners.size / 2
        if (n < 3) return false
        var sharp = 0
        for (i in 1 until n - 1) {
            val ax = corners[i * 2 - 2] - corners[i * 2]; val ay = corners[i * 2 - 1] - corners[i * 2 + 1]
            val bx = corners[i * 2 + 2] - corners[i * 2]; val by = corners[i * 2 + 3] - corners[i * 2 + 1]
            val cosA = (ax * bx + ay * by) / max(1e-4f, hypot(ax, ay) * hypot(bx, by))
            if (cosA > -0.5f) sharp++   // interior angle below 120 degrees
        }
        return sharp >= n - 2
    }

    /** For closed shapes the first and last simplified vertices coincide; merge them. */
    private fun dedupeClosed(corners: FloatArray, tol: Float): FloatArray {
        val n = corners.size / 2
        if (n < 2) return corners
        val first = 0; val last = n - 1
        val d = hypot(corners[first * 2] - corners[last * 2], corners[first * 2 + 1] - corners[last * 2 + 1])
        if (d > tol) return corners
        // Average the two end points into one vertex.
        val out = corners.copyOfRange(0, (n - 1) * 2)
        out[0] = (corners[0] + corners[last * 2]) / 2f
        out[1] = (corners[1] + corners[last * 2 + 1]) / 2f
        return out
    }

    private fun snapLineAngle(x0: Float, y0: Float, x1: Float, y1: Float): Result.Line {
        val len = hypot(x1 - x0, y1 - y0)
        val a = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val snapped = round(a / (PI / 4)) * (PI / 4)
        return if (abs(a - snapped) < Math.toRadians(5.0)) {
            Result.Line(x0, y0, x0 + (len * cos(snapped)).toFloat(), y0 + (len * sin(snapped)).toFloat())
        } else Result.Line(x0, y0, x1, y1)
    }

    /** Snaps each segment of a chain to horizontal/vertical when it is nearly so. */
    private fun snapChain(corners: FloatArray): FloatArray {
        val out = corners.copyOf()
        for (i in 1 until out.size / 2) {
            val dx = out[i * 2] - out[i * 2 - 2]; val dy = out[i * 2 + 1] - out[i * 2 - 1]
            val a = abs(atan2(dy.toDouble(), dx.toDouble()))
            when {
                a < Math.toRadians(6.0) || a > Math.toRadians(174.0) -> out[i * 2 + 1] = out[i * 2 - 1]
                abs(a - PI / 2) < Math.toRadians(6.0) -> out[i * 2] = out[i * 2 - 2]
            }
        }
        return out
    }
}
