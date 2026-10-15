package com.notesplus.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ShapeRecognizerTest {
    private val rnd = Random(42)

    /** Walks along the x,y polyline with small jitter, emitting (x,y,p) samples. */
    private fun scribble(path: FloatArray, jitter: Float = 1.2f, spacing: Float = 2f): FloatArray {
        val dense = ShapeRecognizer.resample(path, (ShapeRecognizer.length(path) / spacing).toInt().coerceAtLeast(8))!!
        val out = ArrayList<Float>()
        for (i in 0 until dense.size / 2) {
            out += dense[i * 2] + (rnd.nextFloat() - 0.5f) * 2 * jitter
            out += dense[i * 2 + 1] + (rnd.nextFloat() - 0.5f) * 2 * jitter
            out += 0.5f
        }
        return out.toFloatArray()
    }

    @Test
    fun `wobbly horizontal stroke becomes a horizontal line`() {
        val r = ShapeRecognizer.recognize(scribble(floatArrayOf(10f, 50f, 210f, 53f)))
        assertTrue("got $r", r is ShapeRecognizer.Result.Line)
        r as ShapeRecognizer.Result.Line
        assertEquals(r.y0, r.y1, 1e-3f)   // snapped to exactly horizontal
    }

    @Test
    fun `rough rectangle snaps to its bounding box`() {
        val r = ShapeRecognizer.recognize(scribble(floatArrayOf(20f, 20f, 220f, 22f, 218f, 140f, 22f, 138f, 20f, 24f)))
        assertTrue("got $r", r is ShapeRecognizer.Result.Rectangle)
        r as ShapeRecognizer.Result.Rectangle
        assertTrue(r.r - r.l > 190f && r.b - r.t > 110f)
    }

    @Test
    fun `rough circle becomes an ellipse`() {
        val path = FloatArray(80 * 2)
        for (i in 0 until 80) {
            val a = 2 * PI * i / 79
            path[i * 2] = (100 + 60 * cos(a)).toFloat(); path[i * 2 + 1] = (100 + 58 * sin(a)).toFloat()
        }
        val r = ShapeRecognizer.recognize(scribble(path, jitter = 2f))
        assertTrue("got $r", r is ShapeRecognizer.Result.Ellipse)
        r as ShapeRecognizer.Result.Ellipse
        assertEquals(100f, r.cx, 4f); assertEquals(60f, r.rx, 5f)
    }

    @Test
    fun `triangle is recognised with three corners`() {
        val r = ShapeRecognizer.recognize(scribble(floatArrayOf(100f, 20f, 190f, 160f, 10f, 160f, 100f, 22f)))
        assertTrue("got $r", r is ShapeRecognizer.Result.Triangle)
        assertEquals(6, (r as ShapeRecognizer.Result.Triangle).xy.size)
    }

    @Test
    fun `an L shape becomes a two segment polyline`() {
        val r = ShapeRecognizer.recognize(scribble(floatArrayOf(20f, 20f, 20f, 150f, 180f, 152f)))
        assertTrue("got $r", r is ShapeRecognizer.Result.Polyline)
        assertEquals(2, r!!.polylines().size)
    }

    @Test
    fun `handwriting-like squiggle is not a shape`() {
        val path = FloatArray(60 * 2)
        for (i in 0 until 60) { path[i * 2] = i * 4f; path[i * 2 + 1] = 50f + (if (i % 2 == 0) 18f else -18f) * (0.4f + rnd.nextFloat()) }
        assertNull(ShapeRecognizer.recognize(scribble(path, jitter = 0.5f)))
    }

    @Test
    fun `tiny strokes are ignored`() {
        assertNull(ShapeRecognizer.recognize(floatArrayOf(0f, 0f, 0.5f, 2f, 1f, 0.5f, 3f, 2f, 0.5f)))
    }

    @Test
    fun `shape builder arrow has a shaft and two head lines`() {
        val lines = ShapeBuilder.build(ShapeKind.ARROW, 0f, 0f, 100f, 0f)
        assertEquals(3, lines.size)
        assertEquals(4, ShapeBuilder.build(ShapeKind.RECTANGLE, 0f, 0f, 50f, 30f).size)
        assertEquals(1, ShapeBuilder.build(ShapeKind.ELLIPSE, 0f, 0f, 50f, 30f).size)
        val sq = ShapeBuilder.build(ShapeKind.RECTANGLE, 0f, 0f, 50f, 30f, constrain = true)
        // Constrained rectangle is a square: the right edge sits at x=50 and the bottom at y=50.
        assertEquals(50f, sq[1][0], 1e-4f); assertEquals(50f, sq[1][3], 1e-4f)
    }
}
