package com.notesplus.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.notesplus.app.ink.PageInsert
import com.notesplus.app.ink.PageKey
import com.notesplus.app.ink.PageSize
import com.notesplus.app.ink.PaperStyle
import java.io.Closeable
import java.io.File

/** Supplies page geometry and raster content for the document view. */
interface PageSource : Closeable {
    val pageCount: Int
    fun pageSize(index: Int): PageSize
    /**
     * Renders the region of page [index] described by [transform] (page points -> bitmap
     * pixels) into [bitmap]. [clip] is in bitmap pixels.
     */
    fun render(index: Int, bitmap: Bitmap, transform: Matrix, clip: Rect?)
    val isPdf: Boolean

    /** Stable identity of the page at [index] (PDF page or inserted page). */
    fun pageKey(index: Int): PageKey = PageKey.Source(index)
}

class PdfPageSource(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    private val sizes: Array<PageSize?>
    override val isPdf = true
    override val pageCount: Int get() = renderer.pageCount

    init {
        sizes = arrayOfNulls(renderer.pageCount)
    }

    /** Reads every page size up front so layout is stable; cheap (no rasterisation). */
    fun preloadSizes() {
        synchronized(lock) {
            for (i in sizes.indices) if (sizes[i] == null) {
                renderer.openPage(i).use { p -> sizes[i] = PageSize(p.width.toFloat(), p.height.toFloat()) }
            }
        }
    }

    override fun pageSize(index: Int): PageSize {
        sizes.getOrNull(index)?.let { return it }
        synchronized(lock) {
            sizes[index]?.let { return it }
            renderer.openPage(index).use { p ->
                val s = PageSize(p.width.toFloat(), p.height.toFloat())
                sizes[index] = s
                return s
            }
        }
    }

    override fun render(index: Int, bitmap: Bitmap, transform: Matrix, clip: Rect?) {
        synchronized(lock) {
            if (closed) return
            renderer.openPage(index).use { page ->
                // PdfRenderer draws onto transparent pixels; give the page a white background.
                if (clip == null) bitmap.eraseColor(Color.WHITE)
                else Canvas(bitmap).drawRect(clip, WHITE)
                page.render(bitmap, clip, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    @Volatile private var closed = false
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    private companion object { val WHITE = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL } }
}

/**
 * The PDF's pages interleaved with the user's inserted blank pages. Indices seen by the
 * view are "logical"; [pageKey] maps them back to stable identities for the ink document.
 */
class CompositePageSource(val base: PageSource) : PageSource {
    private var keys: List<PageKey> = emptyList()
    private var insertsById: Map<String, PageInsert> = emptyMap()
    override val isPdf = true

    init { setInserts(emptyList()) }

    /** Rebuilds the logical page list; returns true when the layout changed. */
    @Synchronized
    fun setInserts(ordered: List<PageInsert>): Boolean {
        val out = ArrayList<PageKey>(base.pageCount + ordered.size)
        ordered.filter { it.after < 0 }.forEach { out += PageKey.Insert(it.id) }
        for (i in 0 until base.pageCount) {
            out += PageKey.Source(i)
            ordered.filter { it.after == i }.forEach { out += PageKey.Insert(it.id) }
        }
        // Inserts that reference pages beyond the end (PDF shrank) go last rather than vanish.
        ordered.filter { it.after >= base.pageCount }.forEach { out += PageKey.Insert(it.id) }
        val changed = out != keys
        keys = out
        insertsById = ordered.associateBy { it.id }
        return changed
    }

    override val pageCount: Int get() = keys.size
    override fun pageKey(index: Int): PageKey = keys[index]
    fun indexOf(key: PageKey): Int = keys.indexOf(key)
    fun insertAt(index: Int): PageInsert? = (keys.getOrNull(index) as? PageKey.Insert)?.let { insertsById[it.id] }

    override fun pageSize(index: Int): PageSize = when (val k = keys[index]) {
        is PageKey.Source -> base.pageSize(k.index)
        is PageKey.Insert -> insertsById[k.id]?.let { PageSize(it.w, it.h) } ?: PageSize.A4
    }

    override fun render(index: Int, bitmap: Bitmap, transform: Matrix, clip: Rect?) {
        when (val k = keys[index]) {
            is PageKey.Source -> base.render(k.index, bitmap, transform, clip)
            is PageKey.Insert -> {
                val ins = insertsById[k.id]
                val c = Canvas(bitmap)
                if (clip != null) c.clipRect(clip)
                c.drawColor(Color.WHITE)
                c.concat(transform)
                if (ins != null) drawPaper(c, PageSize(ins.w, ins.h), ins.paper)
            }
        }
    }

    override fun close() = base.close()

    companion object {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCCD6E3.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB3BFD1.toInt(); style = Paint.Style.FILL }

        /** Draws a paper pattern in page-point coordinates (caller sets the transform). */
        fun drawPaper(c: Canvas, size: PageSize, paper: PaperStyle) {
            when (paper) {
                PaperStyle.PLAIN -> Unit
                PaperStyle.RULED -> { var y = 60f; while (y < size.h - 20f) { c.drawLine(30f, y, size.w - 30f, y, linePaint); y += 28f } }
                PaperStyle.GRID -> {
                    var y = 0f; while (y < size.h) { c.drawLine(0f, y, size.w, y, linePaint); y += 20f }
                    var x = 0f; while (x < size.w) { c.drawLine(x, 0f, x, size.h, linePaint); x += 20f }
                }
                PaperStyle.DOTTED -> { var y = 20f; while (y < size.h) { var x = 20f; while (x < size.w) { c.drawCircle(x, y, 0.8f, dotPaint); x += 20f }; y += 20f } }
            }
        }
    }
}
