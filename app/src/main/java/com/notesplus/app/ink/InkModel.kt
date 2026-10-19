package com.notesplus.app.ink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-disk / on-OneDrive annotation format ("<name>.ink.json").
 *
 * All coordinates are expressed in PDF points (1/72 inch) in the page's own
 * coordinate space with the origin at the top-left corner. That makes strokes
 * independent of screen density and zoom level and lets them be re-applied to
 * the same PDF on any device.
 *
 * The document is designed to merge without conflicts: strokes are immutable
 * and identified by a random id, deletions are recorded as tombstones. Merging
 * two copies is therefore a union of strokes minus the union of tombstones,
 * which means annotations made on two devices while offline are both kept.
 */
@Serializable
enum class ToolType {
    @SerialName("pen") PEN,
    @SerialName("highlighter") HIGHLIGHTER
}

@Serializable
enum class PaperStyle {
    @SerialName("plain") PLAIN,
    @SerialName("ruled") RULED,
    @SerialName("grid") GRID,
    @SerialName("dotted") DOTTED
}

@Serializable
data class PageSize(val w: Float, val h: Float) {
    companion object {
        /** ISO A4 in PDF points. */
        val A4 = PageSize(595f, 842f)
        val LETTER = PageSize(612f, 792f)
    }
}

/**
 * A single stroke. [pts] is a flat array of (x, y, pressure) triples. Pressure is
 * in 0..1 (0.5 when the input device does not report pressure).
 */
@Serializable
data class Stroke(
    val id: String,
    val tool: ToolType,
    /** ARGB colour as a signed 32-bit int (android.graphics.Color convention). */
    val color: Int,
    /** Base width in page points. Pen strokes modulate this with pressure. */
    val width: Float,
    /** Creation time in epoch millis; used for stable draw ordering after merges. */
    val t: Long,
    val pts: FloatArray
) {
    val pointCount: Int get() = pts.size / 3
    fun x(i: Int) = pts[i * 3]
    fun y(i: Int) = pts[i * 3 + 1]
    fun p(i: Int) = pts[i * 3 + 2]

    /** Axis-aligned bounds (minX, minY, maxX, maxY) padded by the maximum stroke width. */
    fun bounds(): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until pointCount) {
            val x = x(i); val y = y(i)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val pad = width * 1.5f
        return floatArrayOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    override fun equals(other: Any?): Boolean = other is Stroke && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

@Serializable
data class InkPage(
    val index: Int,
    val strokes: List<Stroke> = emptyList()
)

/**
 * A blank page the user inserted between PDF pages. It sits after source page [after]
 * (-1 = before the first page); [order] sorts inserts that share the same [after].
 * Like strokes it is immutable and identified by id, so it merges and tombstones cleanly.
 */
@Serializable
data class PageInsert(
    val id: String,
    val after: Int,
    val order: Double,
    val w: Float,
    val h: Float,
    val paper: PaperStyle = PaperStyle.PLAIN,
    val t: Long,
    val strokes: List<Stroke> = emptyList()
)

/** Identifies a page independently of its position: a PDF page by index, an inserted page by id. */
sealed class PageKey {
    data class Source(val index: Int) : PageKey()
    data class Insert(val id: String) : PageKey()
}

@Serializable
data class InkDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** Only present for standalone (non-PDF) notebooks. */
    val pageSize: PageSize? = null,
    /** Only meaningful for standalone notebooks; PDFs take their count from the file. */
    val pageCount: Int = 0,
    val paper: PaperStyle = PaperStyle.PLAIN,
    val pages: List<InkPage> = emptyList(),
    /** Blank pages inserted by the user (see [PageInsert]). */
    val inserts: List<PageInsert> = emptyList(),
    /** Tombstones: ids of strokes (and inserted pages) that were erased. */
    val deleted: Set<String> = emptySet(),
    val modified: Long = 0L
) {
    val isStandalone: Boolean get() = pageSize != null

    fun page(index: Int): InkPage? = pages.firstOrNull { it.index == index }

    fun strokeCount(): Int = pages.sumOf { it.strokes.size } + inserts.sumOf { it.strokes.size }

    fun insert(id: String): PageInsert? = inserts.firstOrNull { it.id == id }

    /** Live inserted pages in document order. */
    fun orderedInserts(): List<PageInsert> = inserts.filterNot { it.id in deleted }.sortedWith(compareBy({ it.after }, { it.order }, { it.t }))

    fun strokesAt(key: PageKey): List<Stroke> = when (key) {
        is PageKey.Source -> page(key.index)?.strokes ?: emptyList()
        is PageKey.Insert -> insert(key.id)?.strokes ?: emptyList()
    }

    fun addStroke(key: PageKey, stroke: Stroke, now: Long = System.currentTimeMillis()): InkDocument = when (key) {
        is PageKey.Source -> addStroke(key.index, stroke, now)
        is PageKey.Insert -> copy(inserts = inserts.map { if (it.id == key.id) it.copy(strokes = it.strokes + stroke) else it }, modified = now)
    }

    fun removeStrokes(key: PageKey, ids: Collection<String>, now: Long = System.currentTimeMillis()): InkDocument = when (key) {
        is PageKey.Source -> removeStrokes(key.index, ids, now)
        is PageKey.Insert -> {
            val idSet = ids.toSet()
            copy(inserts = inserts.map { if (it.id == key.id) it.copy(strokes = it.strokes.filterNot { s -> s.id in idSet }) else it }, deleted = deleted + idSet, modified = now)
        }
    }

    fun addInsert(insert: PageInsert, now: Long = System.currentTimeMillis()): InkDocument =
        copy(inserts = inserts.filterNot { it.id == insert.id } + insert, modified = now)

    /** Removes an inserted page and everything on it (tombstoned so the removal syncs). */
    fun removeInsert(id: String, now: Long = System.currentTimeMillis()): InkDocument {
        val ins = insert(id) ?: return this
        return copy(inserts = inserts.filterNot { it.id == id }, deleted = deleted + id + ins.strokes.map { it.id }, modified = now)
    }

    fun withPage(page: InkPage): InkDocument {
        val others = pages.filter { it.index != page.index }
        val newPages = (others + page).sortedBy { it.index }
        return copy(pages = newPages)
    }

    fun addStroke(pageIndex: Int, stroke: Stroke, now: Long = System.currentTimeMillis()): InkDocument {
        val existing = page(pageIndex) ?: InkPage(pageIndex)
        val newPage = existing.copy(strokes = existing.strokes + stroke)
        return withPage(newPage).copy(modified = now, pageCount = maxOf(pageCount, if (isStandalone) pageIndex + 1 else 0))
    }

    fun removeStrokes(pageIndex: Int, ids: Collection<String>, now: Long = System.currentTimeMillis()): InkDocument {
        val existing = page(pageIndex) ?: return this
        val idSet = ids.toSet()
        val newPage = existing.copy(strokes = existing.strokes.filterNot { it.id in idSet })
        return withPage(newPage).copy(deleted = deleted + idSet, modified = now)
    }

    fun withPageCount(count: Int, now: Long = System.currentTimeMillis()): InkDocument =
        copy(pageCount = count, modified = now)

    /** Lossless-ish fingerprint used to detect whether two docs hold the same annotations. */
    fun contentSignature(): String {
        val sb = StringBuilder()
        sb.append(pageCount).append('|').append(paper).append('|')
        for (p in pages.sortedBy { it.index }) {
            if (p.strokes.isEmpty()) continue
            sb.append(p.index).append(':')
            p.strokes.map { it.id }.sorted().forEach { sb.append(it).append(',') }
            sb.append(';')
        }
        sb.append('|')
        for (ins in inserts.sortedBy { it.id }) {
            sb.append(ins.id).append('@').append(ins.after).append('/').append(ins.order).append(':')
            ins.strokes.map { it.id }.sorted().forEach { sb.append(it).append(',') }
            sb.append(';')
        }
        sb.append('|')
        deleted.sorted().forEach { sb.append(it).append(',') }
        return sb.toString()
    }

    companion object {
        const val FORMAT = "notesplus-ink"
        const val VERSION = 1

        fun emptyForPdf(): InkDocument = InkDocument()

        fun newNotebook(pageSize: PageSize = PageSize.A4, paper: PaperStyle = PaperStyle.RULED, pages: Int = 1): InkDocument =
            InkDocument(pageSize = pageSize, pageCount = pages, paper = paper, modified = System.currentTimeMillis())
    }
}

/** JSON codec for ink documents. Tolerant of unknown keys so newer files still load. */
object InkJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun encode(doc: InkDocument): String = json.encodeToString(doc)

    fun decode(text: String): InkDocument {
        val doc = json.decodeFromString<InkDocument>(text)
        require(doc.format == InkDocument.FORMAT) { "Not a NotesPlus ink document" }
        return doc
    }
}

/**
 * Conflict-free merge of two ink documents (see [InkDocument] docs).
 */
object InkMerge {
    fun merge(local: InkDocument, remote: InkDocument): InkDocument {
        val deleted = local.deleted + remote.deleted
        val pageIndices = (local.pages.map { it.index } + remote.pages.map { it.index }).toSortedSet()
        val pages = pageIndices.map { idx ->
            val byId = LinkedHashMap<String, Stroke>()
            local.page(idx)?.strokes?.forEach { byId[it.id] = it }
            remote.page(idx)?.strokes?.forEach { if (!byId.containsKey(it.id)) byId[it.id] = it }
            val strokes = byId.values.filterNot { it.id in deleted }.sortedBy { it.t }
            InkPage(idx, strokes)
        }.filter { it.strokes.isNotEmpty() }
        // Inserted pages: union by id, strokes merged inside each, tombstones win.
        val insertsById = LinkedHashMap<String, PageInsert>()
        for (ins in local.inserts + remote.inserts) {
            if (ins.id in deleted) continue
            val existing = insertsById[ins.id]
            if (existing == null) insertsById[ins.id] = ins.copy(strokes = ins.strokes.filterNot { it.id in deleted })
            else {
                val byId = LinkedHashMap<String, Stroke>()
                existing.strokes.forEach { byId[it.id] = it }
                ins.strokes.forEach { if (!byId.containsKey(it.id)) byId[it.id] = it }
                insertsById[ins.id] = existing.copy(strokes = byId.values.filterNot { it.id in deleted }.sortedBy { it.t })
            }
        }
        val newer = if (remote.modified > local.modified) remote else local
        val pageSize = local.pageSize ?: remote.pageSize
        // Only standalone notebooks track their own page count; PDFs take it from the file.
        val pageCount = if (pageSize != null) maxOf(local.pageCount, remote.pageCount, (pages.maxOfOrNull { it.index } ?: -1) + 1) else 0
        return InkDocument(
            pageSize = pageSize,
            pageCount = pageCount,
            paper = newer.paper,
            pages = pages,
            inserts = insertsById.values.toList(),
            deleted = deleted,
            modified = maxOf(local.modified, remote.modified)
        )
    }
}
