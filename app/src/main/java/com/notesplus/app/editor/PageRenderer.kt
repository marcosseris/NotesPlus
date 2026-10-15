package com.notesplus.app.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Background rasteriser with two tiers:
 *  - a low-resolution "preview" bitmap per page (cheap, cached, used while scrolling), and
 *  - a crisp "patch" that covers only the visible part of a page at the exact on-screen
 *    scale, rendered once the viewport settles.
 */
class PageRenderer(private val source: PageSource, private val onReady: () -> Unit) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "page-render").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val generation = AtomicInteger()

    private val previewCache = object : LruCache<Int, Bitmap>(max(16, (Runtime.getRuntime().maxMemory() / 1024 / 10).toInt())) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }
    private val previewPending = HashSet<Int>()

    class Patch(val page: Int, val scale: Float, val pageRect: RectF, val bitmap: Bitmap)
    private val patches = HashMap<Int, Patch>()
    private var patchFuture: Future<*>? = null

    @Volatile private var closed = false

    /** Width in pixels the preview bitmaps are rendered at. */
    var previewWidthPx = 900

    fun preview(page: Int): Bitmap? {
        previewCache.get(page)?.let { return it }
        synchronized(previewPending) {
            if (!previewPending.add(page)) return null
        }
        val gen = generation.get()
        executor.execute {
            try {
                if (closed || gen != generation.get()) return@execute
                val size = source.pageSize(page)
                val w = min(previewWidthPx, 2048)
                val scale = w / size.w
                val h = max(1, ceil(size.h * scale).toInt())
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                source.render(page, bmp, Matrix().apply { setScale(scale, scale) }, null)
                if (!closed) { previewCache.put(page, bmp); onReady() }
            } catch (e: Throwable) {
                // Out of memory or a corrupt page: leave the preview empty.
            } finally {
                synchronized(previewPending) { previewPending.remove(page) }
            }
        }
        return null
    }

    /** Returns a usable patch for [page] if it still matches the requested scale/area. */
    fun patch(page: Int, scale: Float, visiblePageRect: RectF): Patch? {
        val p = synchronized(patches) { patches[page] } ?: return null
        if (kotlin.math.abs(p.scale - scale) > 1e-3f) return null
        if (!p.pageRect.contains(visiblePageRect)) return null
        return p
    }

    /**
     * Schedules crisp patches for the given pages. [visible] maps page index to the visible
     * rectangle in page points; [scale] is pixels per point.
     */
    fun requestPatches(scale: Float, visible: Map<Int, RectF>) {
        if (closed) return
        patchFuture?.cancel(false)
        val gen = generation.incrementAndGet()
        patchFuture = executor.submit {
            for ((page, rect) in visible) {
                if (closed || gen != generation.get()) return@submit
                val existing = synchronized(patches) { patches[page] }
                if (existing != null && kotlin.math.abs(existing.scale - scale) < 1e-3f && existing.pageRect.contains(rect)) continue
                val size = source.pageSize(page)
                // Expand a little beyond the viewport so small scrolls stay crisp.
                val padX = rect.width() * 0.25f; val padY = rect.height() * 0.25f
                val r = RectF(max(0f, rect.left - padX), max(0f, rect.top - padY), min(size.w, rect.right + padX), min(size.h, rect.bottom + padY))
                val wPx = ceil(r.width() * scale).toInt().coerceAtLeast(1)
                val hPx = ceil(r.height() * scale).toInt().coerceAtLeast(1)
                if (wPx.toLong() * hPx * 4 > MAX_PATCH_BYTES) continue
                val bmp = try { Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { continue }
                val m = Matrix().apply { setScale(scale, scale); postTranslate(-r.left * scale, -r.top * scale) }
                try { source.render(page, bmp, m, Rect(0, 0, wPx, hPx)) } catch (e: Throwable) { continue }
                if (closed || gen != generation.get()) return@submit
                val old = synchronized(patches) { patches.put(page, Patch(page, scale, r, bmp)) }
                // old bitmap is left to the GC: it may still be mid-draw on the UI thread
                onReady()
            }
            // Drop patches for pages no longer visible.
            val stale = synchronized(patches) { patches.keys.filter { it !in visible.keys }.also { k -> k.forEach { patches.remove(it) } } }
            if (stale.isNotEmpty()) onReady()
        }
    }

    fun invalidatePatches() {
        generation.incrementAndGet()
        synchronized(patches) { patches.clear() }
    }

    fun invalidateAll() {
        invalidatePatches()
        previewCache.evictAll()
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
        executor.shutdownNow()
        synchronized(patches) { patches.clear() }
        previewCache.evictAll()
    }

    private companion object { const val MAX_PATCH_BYTES = 48L * 1024 * 1024 }
}
