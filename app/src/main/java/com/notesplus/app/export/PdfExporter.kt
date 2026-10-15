package com.notesplus.app.export

import android.content.Context
import com.notesplus.app.data.NoteRepository
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.NoteKind
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.InkGeometry
import com.notesplus.app.ink.PageSize
import com.notesplus.app.ink.PaperStyle
import com.notesplus.app.ink.Stroke
import com.notesplus.app.ink.ToolType
import com.notesplus.app.util.Files
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes annotations into a copy of the PDF as real vector content (not bitmaps), so
 * the result stays small, sharp at any zoom and opens in any PDF reader.
 */
class PdfExporter(private val context: Context, private val repo: NoteRepository) {

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    suspend fun export(note: NoteEntity): File = withContext(Dispatchers.IO) {
        val ink = repo.loadInk(note.id)
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, Files.sanitizeFileName(note.title) + ".annotated.pdf")
        val doc = if (note.kind == NoteKind.PDF) PDDocument.load(repo.pdfFile(note.id)) else buildBlankDocument(ink)
        doc.use { d ->
            for (pageIdx in 0 until d.numberOfPages) {
                val strokes = ink.page(pageIdx)?.strokes ?: continue
                if (strokes.isEmpty()) continue
                val page = d.getPage(pageIdx)
                if (note.kind == NoteKind.INK) drawPaper(d, page, ink)
                drawStrokes(d, page, strokes)
            }
            d.save(out)
        }
        out
    }

    private fun buildBlankDocument(ink: InkDocument): PDDocument {
        val size = ink.pageSize ?: PageSize.A4
        val doc = PDDocument()
        repeat(maxOf(1, ink.pageCount)) { doc.addPage(PDPage(PDRectangle(size.w, size.h))) }
        // Paper is drawn lazily in export() only for pages that have strokes; draw it for all here.
        for (i in 0 until doc.numberOfPages) if (ink.page(i)?.strokes.isNullOrEmpty()) drawPaper(doc, doc.getPage(i), ink)
        return doc
    }

    private fun drawPaper(doc: PDDocument, page: PDPage, ink: InkDocument) {
        if (ink.paper == PaperStyle.PLAIN) return
        val box = page.cropBox
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.setStrokingColor(0.80f, 0.85f, 0.92f)
            cs.setLineWidth(0.6f)
            val spacing = if (ink.paper == PaperStyle.RULED) 28f else 20f
            when (ink.paper) {
                PaperStyle.RULED -> {
                    var y = box.upperRightY - 60f
                    while (y > box.lowerLeftY + 20f) { cs.moveTo(box.lowerLeftX + 30f, y); cs.lineTo(box.upperRightX - 30f, y); y -= spacing }
                    cs.stroke()
                }
                PaperStyle.GRID -> {
                    var y = box.upperRightY; while (y > box.lowerLeftY) { cs.moveTo(box.lowerLeftX, y); cs.lineTo(box.upperRightX, y); y -= spacing }
                    var x = box.lowerLeftX; while (x < box.upperRightX) { cs.moveTo(x, box.lowerLeftY); cs.lineTo(x, box.upperRightY); x += spacing }
                    cs.stroke()
                }
                PaperStyle.DOTTED -> {
                    cs.setNonStrokingColor(0.70f, 0.75f, 0.85f)
                    var y = box.upperRightY - spacing
                    while (y > box.lowerLeftY) {
                        var x = box.lowerLeftX + spacing
                        while (x < box.upperRightX) { cs.addRect(x - 0.6f, y - 0.6f, 1.2f, 1.2f); x += spacing }
                        y -= spacing
                    }
                    cs.fill()
                }
                PaperStyle.PLAIN -> Unit
            }
        }
    }

    /**
     * Maps a point from our display space (top-left origin, rotation already applied, in
     * points) to PDF user space for this page, honouring CropBox offset and /Rotate.
     */
    private class Mapper(page: PDPage) {
        private val box: PDRectangle = page.cropBox
        private val rot = ((page.rotation % 360) + 360) % 360
        private val w = box.width
        private val h = box.height
        fun x(dx: Float, dy: Float): Float = when (rot) {
            90 -> box.lowerLeftX + dy
            180 -> box.upperRightX - dx
            270 -> box.lowerLeftX + w - dy
            else -> box.lowerLeftX + dx
        }
        fun y(dx: Float, dy: Float): Float = when (rot) {
            90 -> box.lowerLeftY + dx
            180 -> box.lowerLeftY + dy
            270 -> box.lowerLeftY + h - dx
            else -> box.upperRightY - dy
        }
    }

    private fun drawStrokes(doc: PDDocument, page: PDPage, strokes: List<Stroke>) {
        val m = Mapper(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val hlState = PDExtendedGraphicsState().apply {
                strokingAlphaConstant = 0.40f
                nonStrokingAlphaConstant = 0.40f
                blendMode = BlendMode.MULTIPLY
            }
            val penState = PDExtendedGraphicsState().apply { strokingAlphaConstant = 1f; nonStrokingAlphaConstant = 1f; blendMode = BlendMode.NORMAL }
            var currentTool: ToolType? = null
            for (s in strokes.sortedBy { it.t }) {
                if (s.pointCount == 0) continue
                val r = ((s.color shr 16) and 0xFF) / 255f
                val g = ((s.color shr 8) and 0xFF) / 255f
                val b = (s.color and 0xFF) / 255f
                val a = ((s.color ushr 24) and 0xFF) / 255f
                if (currentTool != s.tool) {
                    cs.setGraphicsStateParameters(if (s.tool == ToolType.HIGHLIGHTER) hlState else penState)
                    currentTool = s.tool
                }
                when (s.tool) {
                    ToolType.PEN -> {
                        if (a < 0.999f) cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = a })
                        cs.setNonStrokingColor(r, g, b)
                        val poly = InkGeometry.outline(InkGeometry.smooth(s.pts), s.width)
                        if (poly.size < 6) continue
                        cs.moveTo(m.x(poly[0], poly[1]), m.y(poly[0], poly[1]))
                        var i = 2
                        while (i < poly.size) { cs.lineTo(m.x(poly[i], poly[i + 1]), m.y(poly[i], poly[i + 1])); i += 2 }
                        cs.closePath()
                        cs.fill()
                        if (a < 0.999f) { currentTool = null }
                    }
                    ToolType.HIGHLIGHTER -> {
                        cs.setStrokingColor(r, g, b)
                        cs.setLineWidth(s.width)
                        cs.setLineCapStyle(1)   // round
                        cs.setLineJoinStyle(1)  // round
                        cs.moveTo(m.x(s.x(0), s.y(0)), m.y(s.x(0), s.y(0)))
                        if (s.pointCount == 1) cs.lineTo(m.x(s.x(0) + 0.1f, s.y(0)), m.y(s.x(0) + 0.1f, s.y(0)))
                        for (i in 1 until s.pointCount) cs.lineTo(m.x(s.x(i), s.y(i)), m.y(s.x(i), s.y(i)))
                        cs.stroke()
                    }
                }
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = COSName.A
    }
}
