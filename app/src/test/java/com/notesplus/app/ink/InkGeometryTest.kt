package com.notesplus.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class InkGeometryTest {
    private val line = Stroke("l", ToolType.PEN, -16777216, 2f, 0L, floatArrayOf(0f, 0f, 0.5f, 100f, 0f, 0.5f))

    @Test
    fun `pressure maps into the configured width range`() {
        assertEquals(2f * InkGeometry.PEN_MIN_FACTOR, InkGeometry.penWidthFor(2f, 0f), 1e-5f)
        assertEquals(2f * InkGeometry.PEN_MAX_FACTOR, InkGeometry.penWidthFor(2f, 1f), 1e-5f)
        assertTrue(InkGeometry.penWidthFor(2f, 0.5f) in 1.0f..2.5f)
    }

    @Test
    fun `eraser hit test respects distance`() {
        assertTrue(InkGeometry.hits(line, 50f, 3f, 5f))
        assertFalse(InkGeometry.hits(line, 50f, 30f, 5f))
        assertTrue(InkGeometry.hitsSegment(line, 50f, -40f, 50f, 40f, 2f))
        assertFalse(InkGeometry.hitsSegment(line, 150f, -40f, 150f, 40f, 2f))
    }

    @Test
    fun `outline encloses the stroke centre line`() {
        val poly = InkGeometry.outline(line.pts, 4f)
        assertTrue(poly.size >= 8)
        // Every centre-line sample should be inside the polygon (ray casting).
        for (x in listOf(0f, 25f, 50f, 75f, 100f)) assertTrue("x=$x", pointInPolygon(poly, x, 0f))
        // A point clearly beyond the width must be outside.
        assertFalse(pointInPolygon(poly, 50f, 10f))
        // Caps: a point just beyond the end but within the radius is inside (round cap).
        assertTrue(pointInPolygon(poly, 101f, 0f))
    }

    @Test
    fun `outline of a single point is a dot`() {
        val poly = InkGeometry.outline(floatArrayOf(5f, 5f, 1f), 3f)
        assertTrue(poly.size >= 16)
        for (i in poly.indices step 2) assertEquals(InkGeometry.penWidthFor(3f, 1f) / 2f, hypot(poly[i] - 5f, poly[i + 1] - 5f), 1e-3f)
    }

    @Test
    fun `smoothing keeps endpoints and increases sample count`() {
        val pts = floatArrayOf(0f, 0f, 0.5f, 10f, 5f, 0.6f, 20f, 0f, 0.7f, 30f, 5f, 0.8f)
        val s = InkGeometry.smooth(pts)
        assertTrue(s.size > pts.size)
        assertEquals(0f, s[0], 1e-5f); assertEquals(0f, s[1], 1e-5f)
        assertEquals(30f, s[s.size - 3], 1e-4f); assertEquals(5f, s[s.size - 2], 1e-4f)
        assertEquals(0.8f, s[s.size - 1], 1e-4f)
    }

    private fun pointInPolygon(poly: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / ((yj - yi).let { if (abs(it) < 1e-9f) 1e-9f else it }) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
