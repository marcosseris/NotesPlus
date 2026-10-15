package com.notesplus.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notesplus.app.NotesPlusApp
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.NoteKind
import com.notesplus.app.editor.BlankPageSource
import com.notesplus.app.editor.DocumentView
import com.notesplus.app.editor.PageSource
import com.notesplus.app.editor.PdfPageSource
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.PageSize
import com.notesplus.app.onedrive.SyncStatus
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
    val strokeCount: Int = 0
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
                    eraserRadius = s.eraserRadius, fingerDraws = s.fingerDrawing, stylusButtonErases = s.stylusButtonErases
                )
            }
        }
    }

    private suspend fun load() {
        try {
            val note = repo.get(noteId) ?: run { _state.update { it.copy(loading = false, error = "Note not found") }; return }
            val ink = repo.loadInk(noteId)
            val source: PageSource = withContext(Dispatchers.IO) {
                if (note.kind == NoteKind.PDF) PdfPageSource(repo.pdfFile(noteId)).also { it.preloadSizes() }
                else BlankPageSource(ink.pageSize ?: PageSize.A4, ink.paper, maxOf(1, ink.pageCount))
            }
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
        _state.update { it.copy(canUndo = canUndo, canRedo = canRedo, unsaved = true, strokeCount = doc.strokeCount(), pageCount = if (doc.isStandalone) doc.pageCount else it.pageCount) }
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
        if (!c.authManager.isSignedIn) { _events.emit(EditorEvent.Message("Sign in to OneDrive in Settings to sync")); return@launch }
        val r = c.syncEngine.sync()
        _events.emit(EditorEvent.Message(if (r.ok) "Synced: ${r.message}" else "Sync failed: ${r.message}"))
    }

    override fun onCleared() {
        flushNow()
        val src = pageSource
        pageSource = null
        // Give the view a moment to detach before the renderer loses its file.
        app.appScope.launch { delay(500); runCatching { src?.close() } }
    }

    companion object { const val SAVE_DEBOUNCE_MS = 900L }
}
