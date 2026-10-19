package com.notesplus.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notesplus.app.NotesPlusApp
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.editor.CompositePageSource
import com.notesplus.app.editor.DocumentView
import com.notesplus.app.ink.PaperStyle
import com.notesplus.app.data.DEFAULT_HIGHLIGHTER_PALETTE
import com.notesplus.app.data.DEFAULT_PEN_PALETTE
import com.notesplus.app.editor.PageSource
import com.notesplus.app.editor.PdfPageSource
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.ShapeKind
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.LruCache
import com.notesplus.app.cloud.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EditorState(
    val loading: Boolean = true,
    val error: String? = null,
    val note: NoteEntity? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val zoom: Float = 1f,
    val tool: DocumentView.Tool = DocumentView.Tool.PEN,
    val penColor: Int = 0xFF1C1C1E.toInt(),
    val penWidth: Float = 2.2f,
    val highlighterColor: Int = 0xFFFFE627.toInt(),
    val highlighterWidth: Float = 14f,
    val eraserRadius: Float = 10f,
    val fingerDraws: Boolean = false,
    val stylusButtonErases: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val unsaved: Boolean = false,
    val sync: SyncStatus = SyncStatus.Idle,
    val strokeCount: Int = 0,
    val shapeKind: ShapeKind = ShapeKind.LINE,
    val autoShape: Boolean = true,
    val railOnRight: Boolean = false,
    val motionPrediction: Boolean = true,
    val straightHighlighter: Boolean = true,
    val recentColors: List<Int> = emptyList(),
    val penPalette: List<Int> = DEFAULT_PEN_PALETTE,
    val highlighterPalette: List<Int> = DEFAULT_HIGHLIGHTER_PALETTE,
    val insertPaper: PaperStyle = PaperStyle.RULED,
    val showPages: Boolean = false
)

sealed class EditorEvent {
    data class ExternalInk(val doc: InkDocument) : EditorEvent()
    data class Message(val text: String) : EditorEvent()
    data class Share(val file: File) : EditorEvent()
}

class EditorViewModel(private val app: NotesPlusApp, private val noteId: String) : ViewModel() {
    private val c = app.container
    private val repo = c.repository

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    /** Owned here so it survives recomposition; handed to the DocumentView. */
    var pageSource: PageSource? = null
        private set
    var initialInk: InkDocument = InkDocument()
        private set

    private var latest: InkDocument? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            repo.externalInkChanges.filter { it == noteId }.collect {
                val doc = repo.loadInk(noteId)
                _events.emit(EditorEvent.ExternalInk(doc))
            }
        }
        viewModelScope.launch { c.syncEngine.status.collect { s -> _state.update { it.copy(sync = s) } } }
        viewModelScope.launch { repo.observeNote(noteId).collect { n -> if (n != null) _state.update { it.copy(note = n) } } }
        viewModelScope.launch {
            val s = c.prefs.current()
            _state.update {
                it.copy(
                    penColor = s.penColor, penWidth = s.penWidth, highlighterColor = s.highlighterColor, highlighterWidth = s.highlighterWidth,
                    eraserRadius = s.eraserRadius, fingerDraws = s.fingerDrawing, stylusButtonErases = s.stylusButtonErases,
                    autoShape = s.autoShape, railOnRight = s.railOnRight, motionPrediction = s.motionPrediction,
                    straightHighlighter = s.straightHighlighter, recentColors = s.recentColors,
                    penPalette = s.penPalette, highlighterPalette = s.highlighterPalette, insertPaper = s.insertPaper,
                    shapeKind = ShapeKind.entries.getOrElse(s.lastShape) { ShapeKind.LINE }
                )
            }
        }
    }

    private suspend fun load() {
        try {
            val note = repo.get(noteId) ?: run { _state.update { it.copy(loading = false, error = "Note not found") }; return }
            val ink = repo.loadInk(noteId)
            val source: PageSource = withContext(Dispatchers.IO) {
                CompositePageSource(PdfPageSource(repo.pdfFile(noteId)).also { it.preloadSizes() }).also { it.setInserts(ink.orderedInserts()) }
            }
            c.syncEngine.markOpen(noteId)
            repo.touchOpened(noteId)
            pageSource = source
            initialInk = ink
            latest = ink
            _state.update { it.copy(loading = false, note = note, pageCount = source.pageCount, strokeCount = ink.strokeCount()) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "Could not open note: ${e.message}") }
        }
    }

    // ---------------------------------------------------------------- ink persistence

    fun onInkChanged(doc: InkDocument, canUndo: Boolean, canRedo: Boolean) {
        latest = doc
        _state.update { it.copy(canUndo = canUndo, canRedo = canRedo, unsaved = true, strokeCount = doc.strokeCount()) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /** Writes the newest document to disk (merging with anything sync wrote meanwhile). */
    suspend fun flush() {
        val doc = latest ?: return
        if (!_state.value.unsaved) return
        val persisted = repo.saveInk(noteId, doc)
        _state.update { it.copy(unsaved = false) }
        if (persisted.contentSignature() != doc.contentSignature()) {
            latest = persisted
            _events.emit(EditorEvent.ExternalInk(persisted))
        }
    }

    fun flushNow() {
        saveJob?.cancel()
        // Use the app scope so the write survives the screen being closed.
        val doc = latest
        if (doc != null && _state.value.unsaved) {
            _state.update { it.copy(unsaved = false) }
            app.appScope.launch { repo.saveInk(noteId, doc) }
        }
    }

    fun onViewportChanged(page: Int, count: Int, zoom: Float) {
        _state.update { it.copy(currentPage = page, pageCount = count, zoom = zoom) }
    }

    // ---------------------------------------------------------------- tools

    fun setTool(t: DocumentView.Tool) = _state.update { it.copy(tool = t) }

    /** Replaces one slot of the active palette and persists it. */
    fun replacePaletteColor(highlighter: Boolean, index: Int, color: Int) {
        val s = _state.value
        val palette = (if (highlighter) s.highlighterPalette else s.penPalette).toMutableList()
        if (index !in palette.indices) return
        palette[index] = color
        _state.update { if (highlighter) it.copy(highlighterPalette = palette, highlighterColor = color) else it.copy(penPalette = palette, penColor = color) }
        viewModelScope.launch {
            if (highlighter) { c.prefs.setHighlighterPalette(palette); c.prefs.setHighlighter(color, s.highlighterWidth) }
            else { c.prefs.setPenPalette(palette); c.prefs.setPen(color, s.penWidth) }
        }
    }

    fun resetPalette(highlighter: Boolean) {
        _state.update { if (highlighter) it.copy(highlighterPalette = DEFAULT_HIGHLIGHTER_PALETTE) else it.copy(penPalette = DEFAULT_PEN_PALETTE) }
        viewModelScope.launch { if (highlighter) c.prefs.setHighlighterPalette(DEFAULT_HIGHLIGHTER_PALETTE) else c.prefs.setPenPalette(DEFAULT_PEN_PALETTE) }
    }

    /** Remembers a colour chosen in the picker so it shows under "Recent". */
    fun rememberColor(color: Int) {
        _state.update { it.copy(recentColors = (listOf(color) + it.recentColors.filter { c -> c != color }).take(12)) }
        viewModelScope.launch { c.prefs.pushRecentColor(color) }
    }

    fun setPen(color: Int = _state.value.penColor, width: Float = _state.value.penWidth) {
        _state.update { it.copy(penColor = color, penWidth = width) }
        viewModelScope.launch { c.prefs.setPen(color, width) }
    }

    fun setHighlighter(color: Int = _state.value.highlighterColor, width: Float = _state.value.highlighterWidth) {
        _state.update { it.copy(highlighterColor = color, highlighterWidth = width) }
        viewModelScope.launch { c.prefs.setHighlighter(color, width) }
    }

    fun setEraserRadius(r: Float) {
        _state.update { it.copy(eraserRadius = r) }
        viewModelScope.launch { c.prefs.setEraserRadius(r) }
    }

    fun setFingerDraws(v: Boolean) {
        _state.update { it.copy(fingerDraws = v) }
        viewModelScope.launch { c.prefs.setFingerDrawing(v) }
    }

    fun setShapeKind(k: ShapeKind) {
        _state.update { it.copy(shapeKind = k, tool = DocumentView.Tool.SHAPE) }
        viewModelScope.launch { c.prefs.setLastShape(k.ordinal) }
    }

    fun setAutoShape(v: Boolean) {
        _state.update { it.copy(autoShape = v) }
        viewModelScope.launch { c.prefs.setAutoShape(v) }
    }

    fun togglePages() = _state.update { it.copy(showPages = !it.showPages) }


    // ---------------------------------------------------------------- page navigator thumbnails

    private val thumbCache = LruCache<Int, Bitmap>(64)

    /** Inserted pages shift logical indices, so thumbnails are re-rendered after a change. */
    fun invalidatePageThumbnails() { thumbCache.evictAll() }

    /** Small raster of page [index] for the page navigator (page content only, no ink). */
    suspend fun pageThumbnail(index: Int, widthPx: Int = 220): Bitmap? {
        thumbCache.get(index)?.let { return it }
        val src = pageSource ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val size = src.pageSize(index)
                val scale = widthPx / size.w
                val bmp = Bitmap.createBitmap(widthPx, (size.h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                src.render(index, bmp, Matrix().apply { setScale(scale, scale) }, null)
                thumbCache.put(index, bmp)
                bmp
            }.getOrNull()
        }
    }

    fun export() = viewModelScope.launch {
        val note = _state.value.note ?: return@launch
        try {
            flush()
            val f = c.exporter.export(note)
            _events.emit(EditorEvent.Share(f))
        } catch (e: Exception) {
            _events.emit(EditorEvent.Message("Export failed: ${e.message}"))
        }
    }

    fun syncNow() = viewModelScope.launch {
        flush()
        if (c.cloudRepository.signedInProviders().isEmpty()) { _events.emit(EditorEvent.Message("Sign in to a drive in Settings to sync")); return@launch }
        val r = c.syncEngine.sync(force = true)
        _events.emit(EditorEvent.Message(if (r.ok) "Synced: ${r.message}" else "Sync failed: ${r.message}"))
    }

    /** Removes every annotation on this PDF (undo is not available afterwards; the deletion syncs). */
    fun clearAllAnnotations() = viewModelScope.launch {
        flush()
        val cleared = repo.clearInk(noteId)
        latest = cleared
        _state.update { it.copy(strokeCount = 0, canUndo = false, canRedo = false) }
        _events.emit(EditorEvent.ExternalInk(cleared))
        _events.emit(EditorEvent.Message("All annotations removed"))
    }

    override fun onCleared() {
        flushNow()
        c.syncEngine.markClosed(noteId)
        // Leaving the note: let the engine bake the annotated PDF soon rather than after the idle delay.
        app.appScope.launch { c.syncScheduler.requestSync(delayMs = 5_000L) }
        val src = pageSource
        pageSource = null
        // Give the view a moment to detach before the renderer loses its file.
        app.appScope.launch { delay(500); runCatching { src?.close() } }
    }

    companion object { const val SAVE_DEBOUNCE_MS = 900L }
}
