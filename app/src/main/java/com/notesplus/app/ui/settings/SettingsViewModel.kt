package com.notesplus.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notesplus.app.NotesPlusApp
import com.notesplus.app.data.Settings
import com.notesplus.app.onedrive.DriveItem
import com.notesplus.app.onedrive.SyncStatus
import com.notesplus.app.onedrive.auth.AuthState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: Settings = Settings(),
    val auth: AuthState = AuthState.SignedOut,
    val sync: SyncStatus = SyncStatus.Idle,
    val log: List<String> = emptyList()
)

/** State of the OneDrive folder browser dialog. */
data class FolderBrowser(
    val path: List<DriveItem> = emptyList(),   // breadcrumb; empty = root
    val folders: List<DriveItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
) {
    val currentId: String? get() = path.lastOrNull()?.id
    val currentPath: String get() = "/" + path.joinToString("/") { it.name }
}

class SettingsViewModel(private val app: NotesPlusApp) : ViewModel() {
    private val c = app.container
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val ui: StateFlow<SettingsUi> = combine(c.prefs.settings, c.authManager.state, c.syncEngine.status, c.syncEngine.log) { s, a, st, log ->
        SettingsUi(s, a, st, log)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    private val _browser = MutableStateFlow<FolderBrowser?>(null)
    val browser: StateFlow<FolderBrowser?> = _browser.asStateFlow()

    fun signIn(activityContext: Context) = viewModelScope.launch { c.authManager.startSignIn(activityContext) }

    fun signOut() = viewModelScope.launch {
        c.authManager.signOut()
        c.syncScheduler.cancelAll()
        _messages.tryEmit("Signed out. Your notes stay on this device.")
    }

    fun saveClientId(id: String) = viewModelScope.launch {
        c.prefs.setClientIdOverride(id)
        _messages.tryEmit("Client ID saved")
    }

    fun setAutoSync(v: Boolean) = viewModelScope.launch { c.prefs.setAutoSync(v); c.syncScheduler.ensurePeriodic() }
    fun setMetered(v: Boolean) = viewModelScope.launch { c.prefs.setSyncOnMetered(v); c.syncScheduler.ensurePeriodic() }
    fun setFingerDrawing(v: Boolean) = viewModelScope.launch { c.prefs.setFingerDrawing(v) }
    fun setStylusButtonErases(v: Boolean) = viewModelScope.launch { c.prefs.setStylusButtonErases(v) }
    fun setExportOnSync(v: Boolean) = viewModelScope.launch { c.prefs.setExportOnSync(v) }
    fun setAutoShape(v: Boolean) = viewModelScope.launch { c.prefs.setAutoShape(v) }
    fun setRailOnRight(v: Boolean) = viewModelScope.launch { c.prefs.setRailOnRight(v) }
    fun setMotionPrediction(v: Boolean) = viewModelScope.launch { c.prefs.setMotionPrediction(v) }
    fun setStraightHighlighter(v: Boolean) = viewModelScope.launch { c.prefs.setStraightHighlighter(v) }

    fun syncNow() = viewModelScope.launch {
        val r = c.syncEngine.sync()
        if (!r.ok) _messages.tryEmit("Sync failed: ${r.message}")
    }

    // ---------------------------------------------------------------- folder browser

    fun openBrowser() { _browser.value = FolderBrowser(); loadFolder(emptyList()) }
    fun closeBrowser() { _browser.value = null }

    fun enter(folder: DriveItem) { val b = _browser.value ?: return; loadFolder(b.path + folder) }
    fun up() { val b = _browser.value ?: return; if (b.path.isNotEmpty()) loadFolder(b.path.dropLast(1)) }

    private fun loadFolder(path: List<DriveItem>) = viewModelScope.launch {
        _browser.value = FolderBrowser(path = path, loading = true)
        try {
            val children = c.graph.children(path.lastOrNull()?.id).filter { it.isFolder }.sortedBy { it.name.lowercase() }
            _browser.value = FolderBrowser(path = path, folders = children, loading = false)
        } catch (e: Exception) {
            _browser.value = FolderBrowser(path = path, loading = false, error = e.message)
        }
    }

    fun createFolder(name: String) = viewModelScope.launch {
        val b = _browser.value ?: return@launch
        try {
            val created = c.graph.createFolder(b.currentId, name)
            loadFolder(b.path + created)
        } catch (e: Exception) { _messages.tryEmit("Could not create folder: ${e.message}") }
    }

    /** Switches the sync folder. Notes that came from the old folder are dropped locally. */
    fun useCurrentFolder() = viewModelScope.launch {
        val b = _browser.value ?: return@launch
        val id = b.currentId
        val path = if (b.path.isEmpty()) "/" else b.currentPath
        if (id == null) { _messages.tryEmit("Pick a folder (the drive root cannot be used)"); return@launch }
        val old = c.prefs.current()
        if (old.syncFolderId != id) {
            for (n in c.repository.all()) {
                val cameFromRemote = (n.pdfRemoteId != null || n.inkRemoteId != null)
                if (cameFromRemote && !n.needsUpload) c.repository.removeLocalOnly(n.id)
                else if (cameFromRemote) c.repository.update(n.copy(pdfRemoteId = null, pdfCTag = null, pdfETag = null, inkRemoteId = null, inkETag = null, pdfDirty = n.kind == com.notesplus.app.data.db.NoteKind.PDF, inkDirty = true))
            }
        }
        c.prefs.setSyncFolder(path, id)
        _browser.value = null
        _messages.tryEmit("Sync folder set to $path")
        c.syncScheduler.requestSync(0L, force = true)
    }
}
