package com.notesplus.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notesplus.app.NotesPlusApp
import com.notesplus.app.cloud.CloudRepository
import com.notesplus.app.cloud.SyncStatus
import com.notesplus.app.cloud.auth.AuthState
import com.notesplus.app.data.db.CloudItemEntity
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.Provider
import com.notesplus.app.ui.common.ThumbnailLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where the browser currently is: a provider and a folder (null = provider root). */
data class Location(val provider: Provider, val folderId: String?)

data class BrowseState(
    val location: Location? = null,
    val breadcrumb: List<CloudItemEntity> = emptyList(),
    val items: List<CloudItemEntity> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Notes keyed by remote id, to show "downloaded" / "annotated" badges. */
    val notesByRemote: Map<String, NoteEntity> = emptyMap(),
    val signedIn: Map<Provider, AuthState> = emptyMap(),
    val opening: String? = null
)

data class RecentState(
    val notes: List<NoteEntity> = emptyList(),
    val sync: SyncStatus = SyncStatus.Idle,
    val dirtyCount: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastSyncStatus: String = ""
)

sealed class HomeEvent {
    data class UpdateAvailable(val versionName: String, val autoInstalling: Boolean) : HomeEvent()
    data class OpenNote(val noteId: String) : HomeEvent()
    data class Message(val text: String) : HomeEvent()
    data class Share(val file: File) : HomeEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val app: NotesPlusApp) : ViewModel() {
    private val c = app.container
    private val cloud: CloudRepository = c.cloudRepository
    val thumbnails = ThumbnailLoader(c.repository)

    private val location = MutableStateFlow<Location?>(null)
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val opening = MutableStateFlow<String?>(null)
    private val breadcrumb = MutableStateFlow<List<CloudItemEntity>>(emptyList())
    /** Per-provider folder the user was last in, so switching drives keeps your place. */
    private val lastFolder = HashMap<Provider, String?>()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val authStates = combine(c.oneDriveAuth.state, c.googleAuth.state) { od, gd -> mapOf(Provider.ONEDRIVE to od, Provider.GOOGLE_DRIVE to gd) }

    private val items = location.flatMapLatest { loc -> if (loc == null) flowOf(emptyList()) else cloud.observeChildren(loc.provider, loc.folderId) }

    val browse: StateFlow<BrowseState> = combine(location, breadcrumb, items, refreshing, error, c.repository.observeNotes(), authStates, opening) { arr ->
        @Suppress("UNCHECKED_CAST")
        BrowseState(
            location = arr[0] as Location?, breadcrumb = arr[1] as List<CloudItemEntity>, items = arr[2] as List<CloudItemEntity>,
            refreshing = arr[3] as Boolean, error = arr[4] as String?,
            notesByRemote = (arr[5] as List<NoteEntity>).associateBy { it.remoteId },
            signedIn = arr[6] as Map<Provider, AuthState>, opening = arr[7] as String?
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseState())

    val recent: StateFlow<RecentState> = combine(c.repository.observeNotes(), c.syncEngine.status, c.repository.observeDirtyCount(), c.prefs.settings) { notes, sync, dirty, s ->
        RecentState(notes, sync, dirty, s.lastSyncAt, s.lastSyncStatus)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentState())

    init {
        viewModelScope.launch { checkForUpdates(manual = false) }
        viewModelScope.launch {
            // Start in the first signed-in drive.
            val first = cloud.signedInProviders().firstOrNull()
            if (first != null && location.value == null) navigate(Location(first, null))
        }
        viewModelScope.launch {
            authStates.collect { states ->
                val loc = location.value
                if (loc != null && states[loc.provider] !is AuthState.SignedIn) {
                    val other = cloud.signedInProviders().firstOrNull()
                    if (other != null) navigate(Location(other, null)) else location.value = null
                } else if (loc == null) {
                    cloud.signedInProviders().firstOrNull()?.let { navigate(Location(it, null)) }
                }
            }
        }
    }

    private var refreshJob: Job? = null

    fun switchProvider(p: Provider) { navigate(Location(p, lastFolder[p])) }

    fun open(item: CloudItemEntity) {
        if (item.isFolder) navigate(Location(item.provider, item.id)) else openPdf(item)
    }

    fun navigateTo(folderId: String?) { location.value?.let { navigate(Location(it.provider, folderId)) } }

    fun up(): Boolean {
        val loc = location.value ?: return false
        if (loc.folderId == null) return false
        val parent = breadcrumb.value.dropLast(1).lastOrNull()?.id
        navigate(Location(loc.provider, parent))
        return true
    }

    private fun navigate(loc: Location) {
        location.value = loc
        lastFolder[loc.provider] = loc.folderId
        error.value = null
        viewModelScope.launch { breadcrumb.value = cloud.breadcrumb(loc.provider, loc.folderId) }
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        val loc = location.value ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!force && !cloud.isStale(loc.provider, loc.folderId)) return@launch
            refreshing.value = true
            val err = cloud.refresh(loc.provider, loc.folderId, force = true)
            if (location.value == loc) error.value = err
            refreshing.value = false
        }
    }

    private fun openPdf(item: CloudItemEntity) {
        if (opening.value != null) return
        viewModelScope.launch {
            opening.value = item.id
            try {
                val note = c.syncEngine.prepareNote(item) { msg -> _events.tryEmit(HomeEvent.Message(msg)) }
                _events.emit(HomeEvent.OpenNote(note.id))
            } catch (e: Exception) {
                _events.emit(HomeEvent.Message("Could not open ${item.name}: ${e.message}"))
            } finally { opening.value = null }
        }
    }

    fun openRecent(note: NoteEntity) { viewModelScope.launch { _events.emit(HomeEvent.OpenNote(note.id)) } }

    fun removeLocal(note: NoteEntity) = viewModelScope.launch {
        c.repository.removeLocal(note.id)
        _events.emit(HomeEvent.Message("Removed from this device. Annotations stay on ${note.provider.label}."))
    }

    fun export(note: NoteEntity) = viewModelScope.launch {
        try { _events.emit(HomeEvent.Share(c.exporter.export(note))) } catch (e: Exception) { _events.emit(HomeEvent.Message("Export failed: ${e.message}")) }
    }

    fun syncNow() = viewModelScope.launch {
        if (cloud.signedInProviders().isEmpty()) { _events.emit(HomeEvent.Message("Sign in to OneDrive or Google Drive in Settings first")); return@launch }
        val r = c.syncEngine.sync(force = true)
        if (!r.ok) _events.emit(HomeEvent.Message("Sync failed: ${r.message}"))
    }

    fun signIn(p: Provider, activityContext: android.content.Context) = viewModelScope.launch { c.auth(p).startSignIn(activityContext) }

    /** Looks for a newer build on GitHub (at most every 6 hours unless [manual]) and installs it when auto-update is on. */
    fun checkForUpdates(manual: Boolean) = viewModelScope.launch {
        val um = c.updateManager
        if (!um.supported) return@launch
        val s = c.prefs.current()
        if (!manual && (!s.autoUpdate || System.currentTimeMillis() - s.lastUpdateCheck < 6 * 60 * 60 * 1000L)) return@launch
        val release = um.check() ?: return@launch
        _events.emit(HomeEvent.UpdateAvailable(release.versionName, autoInstalling = s.autoUpdate))
        if (s.autoUpdate) um.downloadAndInstall(release)
    }

    fun installUpdate() = viewModelScope.launch {
        val st = c.updateManager.state.value
        if (st is com.notesplus.app.update.UpdateState.Available) c.updateManager.downloadAndInstall(st.release)
    }
}
