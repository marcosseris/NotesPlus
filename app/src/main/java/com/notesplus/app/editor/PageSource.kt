package com.notesplus.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.notesplus.app.ink.PageSize
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
