package com.notesplus.app.export

import android.content.Context
import com.notesplus.app.data.NoteRepository
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.InkGeometry
import com.notesplus.app.ink.Stroke
import com.notesplus.app.ink.ToolType
import com.notesplus.app.util.Files
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bakes annotations into a PDF as vector content on a separate, marked layer.
 *
 * The original page streams are never modified: NotesPlus adds one "q" stream in front
 * and one annotation stream behind them, both tagged with a private dictionary key. That
 * makes the operation reversible ([stripLayer]) so the pristine PDF can always be
 * recovered from the annotated file, and other viewers can toggle the layer from their
 * Layers panel because it is also registered as an optional content group.
 */
class PdfExporter(private val context: Context, private val repo: NoteRepository) {

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    /** Annotated copy of [note] in the cache directory (for sharing). */
    suspend fun export(note: NoteEntity): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, Files.sanitizeFileName(note.title) + ".annotated.pdf")
        exportTo(repo.pdfFile(note.id), repo.loadInk(note.id), out)
        out
    }

    /** Writes [original] + [ink] to [out]. [out] may be large; it is written atomically. */
    suspend fun exportTo(original: File, ink: InkDocument, out: File) = withContext(Dispatchers.IO) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        PDDocument.load(original).use { doc ->
            // Make sure we never stack layers: strip any previous NotesPlus layer first.
            stripLayerIn(doc)
            val group = ensureLayerGroup(doc)
            for (pageIdx in 0 until doc.numberOfPages) {
                val strokes = ink.page(pageIdx)?.strokes ?: continue
                if (strokes.isEmpty()) continue
                drawStrokes(doc, doc.getPage(pageIdx), strokes, group)
            }
            doc.save(tmp)
        }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
    }

    /**
     * Removes the NotesPlus layer from [file] in place. Returns true when the file carried
     * one (i.e. it was an annotated PDF written by this app).
     */
    suspend fun stripLayer(file: File): Boolean = withContext(Dispatchers.IO) {
        val changed = PDDocument.load(file).use { doc ->
            val removed = stripLayerIn(doc)
            if (removed) {
                val tmp = File(file.parentFile, file.name + ".strip.tmp")
                doc.save(tmp)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
            removed
        }
        changed
    }

    private fun stripLayerIn(doc: PDDocument): Boolean {
        var removedAny = false
        for (page in doc.pages) {
            val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS) ?: continue
            val array: COSArray = when (contents) {
                is COSArray -> contents
                is COSStream -> COSArray().apply { add(contents) }
                else -> continue
            }
            val kept = COSArray()
            var removed = false
            for (i in 0 until array.size()) {
                val obj = array.getObject(i)
                if (obj is COSStream && obj.getBoolean(LAYER_KEY, false)) removed = true else kept.add(array.get(i))
            }
            if (removed) {
                removedAny = true
                when (kept.size()) {
                    0 -> page.cosObject.removeItem(COSName.CONTENTS)
                    1 -> page.cosObject.setItem(COSName.CONTENTS, kept.get(0))
                    else -> page.cosObject.setItem(COSName.CONTENTS, kept)
                }
            }
        }
        return removedAny
    }

    private fun ensureLayerGroup(doc: PDDocument): PDOptionalContentGroup {
        val catalog = doc.documentCatalog
        val props = catalog.ocProperties ?: PDOptionalContentProperties().also { catalog.ocProperties = it }
        props.getGroup(LAYER_NAME)?.let { return it }
        val group = PDOptionalContentGroup(LAYER_NAME)
        props.addGroup(group)
        return group
    }

    /**
     * Maps a point from display space (top-left origin, rotation applied, points) to PDF
     * user space for this page, honouring the CropBox offset and /Rotate.
     */
    private class Mapper(page: PDPage) {
        private val box: PDRectangle = page.cropBox
        private val rot = ((page.rotation % 360) + 360) % 360
        private val w = box.width
        private val h = box.height
        fun x(dx: Float, dy: Float): Float = when (rot) { 90 -> box.lowerLeftX + dy; 180 -> box.upperRightX - dx; 270 -> box.lowerLeftX + w - dy; else -> box.lowerLeftX + dx }
        fun y(dx: Float, dy: Float): Float = when (rot) { 90 -> box.lowerLeftY + dx; 180 -> box.lowerLeftY + dy; 270 -> box.lowerLeftY + h - dx; else -> box.upperRightY - dy }
    }

    private fun drawStrokes(doc: PDDocument, page: PDPage, strokes: List<Stroke>, group: PDOptionalContentGroup) {
        val m = Mapper(page)
        // 1. Prepend a marked "q" so the original content's graphics state cannot leak into ours.
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
        val array: COSArray = when (contents) {
            is COSArray -> contents
            null -> COSArray()
            else -> COSArray().apply { add(contents) }
        }
        val save = doc.document.createCOSStream()
        save.createOutputStream().use { it.write("q\n".toByteArray(Charsets.US_ASCII)) }
        save.setBoolean(LAYER_KEY, true)
        array.add(0, save)
        page.cosObject.setItem(COSName.CONTENTS, array)

        // 2. Append our stream (PdfBox adds it to the same array), restore state, then draw.
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, false).use { cs ->
            cs.restoreGraphicsState()
            cs.beginMarkedContent(COSName.OC, group)
            val hlState = PDExtendedGraphicsState().apply { strokingAlphaConstant = 0.40f; nonStrokingAlphaConstant = 0.40f; blendMode = BlendMode.MULTIPLY }
            val penState = PDExtendedGraphicsState().apply { strokingAlphaConstant = 1f; nonStrokingAlphaConstant = 1f; blendMode = BlendMode.NORMAL }
            var currentTool: ToolType? = null
            for (s in strokes.sortedBy { it.t }) {
                if (s.pointCount == 0) continue
                val r = ((s.color shr 16) and 0xFF) / 255f
                val g = ((s.color shr 8) and 0xFF) / 255f
                val b = (s.color and 0xFF) / 255f
                if (currentTool != s.tool) {
                    cs.setGraphicsStateParameters(if (s.tool == ToolType.HIGHLIGHTER) hlState else penState)
                    currentTool = s.tool
                }
                when (s.tool) {
                    ToolType.PEN -> {
                        cs.setNonStrokingColor(r, g, b)
                        val poly = InkGeometry.outline(InkGeometry.smooth(s.pts), s.width)
                        if (poly.size < 6) continue
                        cs.moveTo(m.x(poly[0], poly[1]), m.y(poly[0], poly[1]))
                        var i = 2
                        while (i < poly.size) { cs.lineTo(m.x(poly[i], poly[i + 1]), m.y(poly[i], poly[i + 1])); i += 2 }
                        cs.closePath()
                        cs.fill()
                    }
                    ToolType.HIGHLIGHTER -> {
                        cs.setStrokingColor(r, g, b)
                        cs.setLineWidth(s.width)
                        cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                        cs.moveTo(m.x(s.x(0), s.y(0)), m.y(s.x(0), s.y(0)))
                        if (s.pointCount == 1) cs.lineTo(m.x(s.x(0) + 0.1f, s.y(0)), m.y(s.x(0) + 0.1f, s.y(0)))
                        for (i in 1 until s.pointCount) cs.lineTo(m.x(s.x(i), s.y(i)), m.y(s.x(i), s.y(i)))
                        cs.stroke()
                    }
                }
            }
            cs.endMarkedContent()
        }
        // 3. Tag the stream PdfBox just appended so it can be stripped later.
        val finalArray = page.cosObject.getDictionaryObject(COSName.CONTENTS) as? COSArray
        (finalArray?.getObject(finalArray.size() - 1) as? COSStream)?.setBoolean(LAYER_KEY, true)
    }

    companion object {
        val LAYER_KEY: COSName = COSName.getPDFName("NotesPlusLayer")
        const val LAYER_NAME = "NotesPlus annotations"
    }
}
