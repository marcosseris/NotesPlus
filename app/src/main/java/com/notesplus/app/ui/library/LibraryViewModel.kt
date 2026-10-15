package com.notesplus.app.ui.library

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notesplus.app.NotesPlusApp
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.ink.PaperStyle
import com.notesplus.app.onedrive.SyncStatus
import com.notesplus.app.onedrive.auth.AuthState
import com.notesplus.app.ui.common.ThumbnailLoader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed class LibraryFilter {
    data object All : LibraryFilter()
    data object Notebooks : LibraryFilter()
    data object Pdfs : LibraryFilter()
    data class Folder(val dir: String) : LibraryFilter()
}

enum class LibrarySort { RECENT, TITLE }

data class LibraryState(
    val notes: List<NoteEntity> = emptyList(),
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.All,
    val sort: LibrarySort = LibrarySort.RECENT,
    val auth: AuthState = AuthState.SignedOut,
    val sync: SyncStatus = SyncStatus.Idle,
    val lastSyncAt: Long = 0L,
    val lastSyncStatus: String = "",
    val dirtyCount: Int = 0,
    val busy: Boolean = false
) {
    val folders: List<String> get() = notes.map { it.relativeDir }.filter { it.isNotEmpty() }.distinct().sorted()

    val filtered: List<NoteEntity>
        get() {
            val byFilter = when (val f = filter) {
                LibraryFilter.All -> notes
                LibraryFilter.Notebooks -> notes.filter { it.kind == com.notesplus.app.data.db.NoteKind.INK }
                LibraryFilter.Pdfs -> notes.filter { it.kind == com.notesplus.app.data.db.NoteKind.PDF }
                is LibraryFilter.Folder -> notes.filter { it.relativeDir == f.dir || it.relativeDir.startsWith(f.dir + "/") }
            }
            val byQuery = if (query.isBlank()) byFilter else byFilter.filter { it.title.contains(query, ignoreCase = true) || it.relativeDir.contains(query, ignoreCase = true) }
            return when (sort) {
                LibrarySort.RECENT -> byQuery.sortedByDescending { it.modifiedAt }
                LibrarySort.TITLE -> byQuery.sortedBy { it.title.lowercase() }
            }
        }
}

class LibraryViewModel(private val app: NotesPlusApp) : ViewModel() {
    private val c = app.container
    private val query = MutableStateFlow("")
    private val busy = MutableStateFlow(false)
    private val filter = MutableStateFlow<LibraryFilter>(LibraryFilter.All)
    private val sort = MutableStateFlow(LibrarySort.RECENT)
    val thumbnails = ThumbnailLoader(c.repository)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<LibraryState> = combine(
        c.repository.observeNotes(), query, c.authManager.state, c.syncEngine.status, c.prefs.settings, c.repository.observeDirtyCount(), busy, filter, sort
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        LibraryState(
            notes = arr[0] as List<NoteEntity>, query = arr[1] as String, auth = arr[2] as AuthState, sync = arr[3] as SyncStatus,
            lastSyncAt = (arr[4] as com.notesplus.app.data.Settings).lastSyncAt,
            lastSyncStatus = (arr[4] as com.notesplus.app.data.Settings).lastSyncStatus,
            dirtyCount = arr[5] as Int, busy = arr[6] as Boolean,
            filter = arr[7] as LibraryFilter, sort = arr[8] as LibrarySort
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    fun setQuery(q: String) { query.value = q }
    fun setFilter(f: LibraryFilter) { filter.value = f }
    fun setSort(s: LibrarySort) { sort.value = s }

    fun syncNow() = viewModelScope.launch {
        if (!c.authManager.isSignedIn) { _messages.tryEmit("Sign in to OneDrive in Settings first"); return@launch }
        val r = c.syncEngine.sync()
        if (!r.ok) _messages.tryEmit("Sync failed: ${r.message}")
    }

    fun createNotebook(title: String, paper: PaperStyle, onCreated: (String) -> Unit) = viewModelScope.launch {
        busy.value = true
        try { onCreated(c.repository.createNotebook(title.ifBlank { "Untitled notebook" }, paper).id) }
        catch (e: Exception) { _messages.tryEmit("Could not create notebook: ${e.message}") }
        finally { busy.value = false }
    }

    fun importPdf(uri: Uri, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        busy.value = true
        try {
            val name = queryDisplayName(uri)
            val note = c.repository.importPdf(uri, name)
            _messages.tryEmit("Imported ${note.title}")
            onDone(note.id)
        } catch (e: Exception) {
            _messages.tryEmit("Import failed: ${e.message}")
            onDone(null)
        } finally { busy.value = false }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path ?: return null).name
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull()
    }

    fun rename(id: String, title: String) = viewModelScope.launch { c.repository.rename(id, title) }

    fun delete(id: String, alsoOnOneDrive: Boolean) = viewModelScope.launch {
        c.repository.delete(id, alsoOnOneDrive)
        _messages.tryEmit(if (alsoOnOneDrive) "Deleted (will be removed from OneDrive on next sync)" else "Removed from this device")
    }

    fun export(note: NoteEntity, onReady: (File) -> Unit) = viewModelScope.launch {
        busy.value = true
        try { onReady(c.exporter.export(note)) } catch (e: Exception) { _messages.tryEmit("Export failed: ${e.message}") }
        finally { busy.value = false }
    }
}
