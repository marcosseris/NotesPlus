package com.notesplus.app.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.notesplus.app.data.NoteRepository
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.ink.InkGeometry
import com.notesplus.app.ink.PageSize
import com.notesplus.app.ink.ToolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Renders and caches a small preview of a note's first page, with its annotations. */
class ThumbnailLoader(private val repo: NoteRepository) {

    suspend fun load(note: NoteEntity, widthPx: Int = 360): Bitmap? = withContext(Dispatchers.IO) {
        val cache = repo.thumbnailFile(note.id)
        if (cache.exists() && cache.lastModified() >= note.modifiedAt) {
            android.graphics.BitmapFactory.decodeFile(cache.path)?.let { return@withContext it }
        }
        val bmp = runCatching { render(note, widthPx) }.getOrNull() ?: return@withContext null
        cache.parentFile?.mkdirs()
        runCatching { FileOutputStream(cache).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        bmp
    }

    private suspend fun render(note: NoteEntity, widthPx: Int): Bitmap? {
        val ink = repo.loadInk(note.id)
        val size: PageSize
        val bmp: Bitmap
        val f: File = repo.pdfFile(note.id)
        if (!f.exists()) return null
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { p ->
                    size = PageSize(p.width.toFloat(), p.height.toFloat())
                    val scale = widthPx / size.w
                    bmp = Bitmap.createBitmap(widthPx, (size.h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        // Overlay first-page strokes.
        val strokes = ink.page(0)?.strokes ?: return bmp
        val c = Canvas(bmp)
        val scale = widthPx / size.w
        c.scale(scale, scale)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; com.notesplus.app.editor.DocumentView.applyHighlighterBlend(this) }
        for (s in strokes) {
            val path = Path()
            if (s.tool == ToolType.PEN) {
                val poly = InkGeometry.outline(InkGeometry.smooth(s.pts), s.width)
                if (poly.size < 6) continue
                path.moveTo(poly[0], poly[1]); var i = 2
                while (i < poly.size) { path.lineTo(poly[i], poly[i + 1]); i += 2 }
                path.close(); fill.color = s.color; c.drawPath(path, fill)
            } else {
                path.moveTo(s.x(0), s.y(0)); for (i in 1 until s.pointCount) path.lineTo(s.x(i), s.y(i))
                hl.color = s.color; hl.alpha = com.notesplus.app.editor.DocumentView.HL_ALPHA; hl.strokeWidth = s.width; c.drawPath(path, hl)
            }
        }
        return bmp
    }
}
