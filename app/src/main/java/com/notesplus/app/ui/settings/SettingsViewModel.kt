package com.notesplus.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notesplus.app.NotesPlusApp
import com.notesplus.app.cloud.SyncStatus
import com.notesplus.app.cloud.auth.AuthState
import com.notesplus.app.data.Settings
import com.notesplus.app.data.WriteBackMode
import com.notesplus.app.data.db.Provider
import com.notesplus.app.update.UpdateState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: Settings = Settings(),
    val auth: Map<Provider, AuthState> = emptyMap(),
    val sync: SyncStatus = SyncStatus.Idle,
    val log: List<String> = emptyList(),
    val update: UpdateState = UpdateState.Idle,
    val hasGithubToken: Boolean = false
)

class SettingsViewModel(private val app: NotesPlusApp) : ViewModel() {
    private val c = app.container
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val tokenPresent = kotlinx.coroutines.flow.MutableStateFlow(c.updateManager.token != null)

    val ui: StateFlow<SettingsUi> = combine(
        c.prefs.settings, c.oneDriveAuth.state, c.googleAuth.state, c.syncEngine.status, c.syncEngine.log, c.updateManager.state, tokenPresent
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        SettingsUi(
            arr[0] as Settings, mapOf(Provider.ONEDRIVE to arr[1] as AuthState, Provider.GOOGLE_DRIVE to arr[2] as AuthState),
            arr[3] as SyncStatus, arr[4] as List<String>, arr[5] as UpdateState, arr[6] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    val updatesSupported: Boolean get() = c.updateManager.supported

    fun setAutoUpdate(v: Boolean) = viewModelScope.launch { c.prefs.setAutoUpdate(v) }
    fun saveGithubToken(t: String) { c.updateManager.token = t; tokenPresent.value = c.updateManager.token != null; _messages.tryEmit(if (t.isBlank()) "Token removed" else "Token saved") }
    fun checkForUpdates() = viewModelScope.launch {
        val r = c.updateManager.check()
        if (r == null && c.updateManager.state.value is UpdateState.UpToDate) _messages.tryEmit("You're on the latest build")
    }
    fun installUpdate() = viewModelScope.launch {
        (c.updateManager.state.value as? UpdateState.Available)?.let { c.updateManager.downloadAndInstall(it.release) }
    }

    fun signIn(p: Provider, activityContext: Context) = viewModelScope.launch { c.auth(p).startSignIn(activityContext) }

    fun signOut(p: Provider) = viewModelScope.launch {
        c.auth(p).signOut()
        c.cloudRepository.invalidateCache(p)
        c.repository.removeAllFor(p)
        if (c.cloudRepository.signedInProviders().isEmpty()) c.syncScheduler.cancelAll()
        _messages.tryEmit("Signed out of ${p.label}. Downloaded copies were removed; your annotations stay on the drive.")
    }

    fun saveClientId(p: Provider, id: String) = viewModelScope.launch { c.prefs.setClientIdOverride(p, id); _messages.tryEmit("Client ID saved") }
    fun setAutoSync(v: Boolean) = viewModelScope.launch { c.prefs.setAutoSync(v); c.syncScheduler.ensurePeriodic() }
    fun setMetered(v: Boolean) = viewModelScope.launch { c.prefs.setSyncOnMetered(v); c.syncScheduler.ensurePeriodic() }
    fun setWriteBack(v: WriteBackMode) = viewModelScope.launch { c.prefs.setWriteBack(v) }
    fun setFingerDrawing(v: Boolean) = viewModelScope.launch { c.prefs.setFingerDrawing(v) }
    fun setStylusButtonErases(v: Boolean) = viewModelScope.launch { c.prefs.setStylusButtonErases(v) }
    fun setAutoShape(v: Boolean) = viewModelScope.launch { c.prefs.setAutoShape(v) }
    fun setRailOnRight(v: Boolean) = viewModelScope.launch { c.prefs.setRailOnRight(v) }
    fun setMotionPrediction(v: Boolean) = viewModelScope.launch { c.prefs.setMotionPrediction(v) }
    fun setStraightHighlighter(v: Boolean) = viewModelScope.launch { c.prefs.setStraightHighlighter(v) }
    fun setInsertPaper(v: com.notesplus.app.ink.PaperStyle) = viewModelScope.launch { c.prefs.setInsertPaper(v) }
    fun resetPalettes() = viewModelScope.launch {
        c.prefs.setPenPalette(com.notesplus.app.data.DEFAULT_PEN_PALETTE)
        c.prefs.setHighlighterPalette(com.notesplus.app.data.DEFAULT_HIGHLIGHTER_PALETTE)
        _messages.tryEmit("Palettes reset to defaults")
    }

    fun syncNow() = viewModelScope.launch {
        val r = c.syncEngine.sync(force = true)
        if (!r.ok) _messages.tryEmit("Sync failed: ${r.message}")
    }

    fun clearCache() = viewModelScope.launch {
        Provider.entries.forEach { c.cloudRepository.invalidateCache(it) }
        _messages.tryEmit("Folder cache cleared; it refills as you browse")
    }
}
