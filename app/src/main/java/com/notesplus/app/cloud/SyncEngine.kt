package com.notesplus.app.cloud

import android.util.Log
import com.notesplus.app.cloud.auth.NotSignedInException
import com.notesplus.app.data.NoteRepository
import com.notesplus.app.data.Prefs
import com.notesplus.app.data.WriteBackMode
import com.notesplus.app.data.db.CloudItemEntity
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.Provider
import com.notesplus.app.export.PdfExporter
import com.notesplus.app.ink.InkJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.UUID

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Running(val message: String) : SyncStatus()
    data class Failed(val message: String) : SyncStatus()
}

data class SyncResult(val ok: Boolean, val message: String, val uploaded: Int = 0, val pulled: Int = 0, val writeBackLater: Boolean = false)

/**
 * Keeps opened notes and their drives in step.
 *
 *  - [prepareNote] opens a PDF from the browser: downloads it (stripping any NotesPlus
 *    layer so the local copy is pristine), merges a remote sidecar if one exists.
 *  - [sync] pushes dirty sidecars (conflict-aware), bakes annotations back into the PDF on
 *    the drive once the note has been idle, and pulls remote changes for recent notes.
 */
class SyncEngine(
    private val repo: NoteRepository,
    private val cloud: CloudRepository,
    private val prefs: Prefs,
    private val exporter: PdfExporter,
    private val cacheDir: File
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Notes currently open in the editor: their PDF write-back waits until they are idle. */
    private val openNotes: MutableSet<String> = Collections.synchronizedSet(HashSet())
    fun markOpen(id: String) { openNotes += id }
    fun markClosed(id: String) { openNotes -= id }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _log.value = (_log.value + "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $msg").takeLast(80)
    }
    private fun progress(msg: String) { _status.value = SyncStatus.Running(msg) }

    // ------------------------------------------------------------------ opening

    /**
     * Makes [item] (a PDF in the browser) available locally and returns its note. Progress
     * messages go to [onProgress]. Safe to call for an already-downloaded note: it then only
     * checks for remote changes.
     */
    private val prepareLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * Makes [item] available locally and returns its note.
     *
     * A PDF already on the device is returned at once (no network); call [refreshOpened]
     * afterwards to pick up remote changes in the background. Otherwise the PDF is downloaded,
     * reporting progress through [onBytes]. Annotations are never fetched here: that also happens
     * in [refreshOpened] and merges into an open editor live.
     *
     * [markOpened] = false is used for background pre-downloads, which stay out of Recent.
     */
    suspend fun prepareNote(
        item: CloudItemEntity,
        markOpened: Boolean = true,
        onProgress: (String) -> Unit = {},
        onBytes: ((Long, Long) -> Unit)? = null
    ): NoteEntity = withContext(Dispatchers.IO) {
        prepareLocks.getOrPut("${item.provider}:${item.id}") { Mutex() }.withLock {
            val existing = repo.byRemote(item.provider, item.id)
            val now = System.currentTimeMillis()
            if (existing != null && repo.pdfFile(existing.id).exists() && existing.pageCount > 0) {
                val n = if (markOpened) existing.copy(lastOpenedAt = now) else existing
                if (markOpened) repo.update(n)
                return@withLock n
            }
            val provider = cloud.providers.getValue(item.provider)
            val pathLabel = cloud.pathLabel(item.provider, item.parentId)
            val remote = provider.getFile(item.id) ?: throw CloudException(404, null, "This file is no longer on ${item.provider.label}")
            val id = existing?.id ?: UUID.randomUUID().toString()
            val pdf = repo.pdfFile(id)
            val converting = !remote.isPdf
            onProgress(if (converting) "Converting ${item.name} to PDF…" else "Downloading ${item.name}…")
            provider.downloadAsPdf(remote, pdf, onBytes)
            if (!converting) runCatching { exporter.stripLayer(pdf) }.onFailure { log("Could not inspect ${item.name} for an annotation layer: ${it.message}") }
            repo.ensureInkFile(id)
            val pages = repo.countPdfPages(pdf).coerceAtLeast(0)
            val note = (existing ?: NoteEntity(
                id = id, provider = item.provider, remoteId = item.id, parentId = item.parentId, fileName = item.name,
                pathLabel = pathLabel, createdAt = now, modifiedAt = now, lastOpenedAt = 0L
            )).copy(
                fileName = remote.name, parentId = remote.parentId ?: item.parentId, pathLabel = pathLabel,
                pageCount = pages, sizeBytes = remote.size, pdfVersion = remote.version, converted = converting,
                lastOpenedAt = if (markOpened) now else (existing?.lastOpenedAt ?: 0L), syncError = null
            )
            repo.upsert(note)
            note
        }
    }

    /**
     * Background follow-up to [prepareNote]: downloads a newer PDF if the drive has one
     * (announced through [NoteRepository.pdfReplaced]) and merges remote annotations.
     */
    suspend fun refreshOpened(noteId: String, checkPdf: Boolean = true) = withContext(Dispatchers.IO) {
        val note = repo.get(noteId) ?: return@withContext
        if (!cloud.isSignedIn(note.provider)) return@withContext
        val provider = cloud.providers.getValue(note.provider)
        try {
            coroutineScope {
                val pdfJob = if (checkPdf) async { refreshPdf(provider, note) } else null
                val inkJob = async { pullSidecar(provider, note) }
                pdfJob?.await(); inkJob.await()
            }
        } catch (e: Exception) {
            log("Could not refresh ${note.title}: ${e.message}")
        }
    }

    /** Replaces the local PDF when the drive has a different version. Returns true when it did. */
    private suspend fun refreshPdf(provider: CloudProvider, note: NoteEntity): Boolean {
        val remote = provider.getFile(note.remoteId) ?: return false
        if (remote.version == null || remote.version == note.pdfVersion) return false
        log("${note.title} changed on the drive; downloading")
        val tmp = File(repo.noteDir(note.id), "incoming.pdf")
        provider.downloadAsPdf(remote, tmp)
        if (remote.isPdf) runCatching { exporter.stripLayer(tmp) }
        if (!tmp.renameTo(repo.pdfFile(note.id))) { tmp.copyTo(repo.pdfFile(note.id), overwrite = true); tmp.delete() }
        repo.thumbnailFile(note.id).delete()
        val fresh = repo.get(note.id) ?: note
        repo.update(fresh.copy(pdfVersion = remote.version, fileName = remote.name, sizeBytes = remote.size, pageCount = repo.countPdfPages(repo.pdfFile(note.id)).coerceAtLeast(0)))
        repo.notifyPdfReplaced(note.id)
        return true
    }

    /** Cache of ".ink" folder ids per document folder (provider:parentId -> folder id). */
    private val inkFolders = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun inkFolder(provider: CloudProvider, parentId: String, create: Boolean): String? {
        val key = "${provider.kind}:$parentId"
        inkFolders[key]?.let { return it }
        val found = if (create) provider.ensureFolder(parentId, CloudProvider.INK_FOLDER)
        else provider.findChild(parentId, CloudProvider.INK_FOLDER)?.takeIf { it.isFolder }
        return found?.id?.also { inkFolders[key] = it }
    }

    /**
     * Brings remote annotations into the local copy. Looks in the hidden .ink folder first and
     * also at the old location next to the document; a sidecar found there is merged in and
     * scheduled to move (uploaded to .ink, then deleted) so nothing is ever lost.
     */
    private suspend fun pullSidecar(provider: CloudProvider, note: NoteEntity): NoteEntity = coroutineScope {
        val parent = note.parentId ?: return@coroutineScope note
        var n = note
        // Look in both places at once; the old location stops being checked once migrated.
        val skipLegacy = prefs.current().inkFolderMigrated && n.inkRemoteId != null && n.legacyInkRemoteId == null
        val legacyLookup = if (skipLegacy) null else async { provider.findChild(parent, n.inkFileName)?.takeIf { !it.isFolder } }
        val inkLookup = async {
            // Known sidecar id: one request instead of folder + name lookups.
            n.inkRemoteId?.let { id -> runCatching { provider.getFile(id) }.getOrNull()?.takeIf { !it.isFolder && it.name.equals(n.inkFileName, true) } }
                ?: inkFolder(provider, parent, create = false)?.let { folderId -> provider.findChild(folderId, n.inkFileName)?.takeIf { !it.isFolder } }
        }
        val legacy = legacyLookup?.await()
        val remoteInkFound = inkLookup.await()

        // 1. Legacy sidecar next to the document (layout used before .ink).
        if (legacy != null && legacy.id != remoteInkFound?.id) {
            val alreadyKnown = legacy.id == n.inkRemoteId && legacy.version == n.inkVersion
            if (!alreadyKnown && legacy.id != n.legacyInkRemoteId) {
                val doc = runCatching { InkJson.decode(provider.downloadText(legacy)) }.getOrNull()
                if (doc != null) repo.mergeRemoteInk(n.id, doc) else log("Ignoring unreadable ${legacy.name}")
            }
            val fresh = repo.get(n.id) ?: n
            n = fresh.copy(
                legacyInkRemoteId = legacy.id, inkDirty = true,
                inkRemoteId = if (fresh.inkRemoteId == legacy.id) null else fresh.inkRemoteId,
                inkVersion = if (fresh.inkRemoteId == legacy.id) null else fresh.inkVersion
            )
            repo.update(n)
            log("Annotations for ${n.title} will move into ${CloudProvider.INK_FOLDER}/")
        }

        // 2. Sidecar in the .ink folder.
        val remoteInk = remoteInkFound ?: return@coroutineScope n
        if (remoteInk.id == n.inkRemoteId && remoteInk.version != null && remoteInk.version == n.inkVersion) return@coroutineScope n
        val text = provider.downloadText(remoteInk)
        val doc = runCatching { InkJson.decode(text) }.getOrElse { log("Ignoring unreadable ${remoteInk.name}"); return@coroutineScope n }
        val (_, localHadMore) = repo.mergeRemoteInk(n.id, doc)
        val fresh = repo.get(n.id) ?: n
        val updated = fresh.copy(inkRemoteId = remoteInk.id, inkVersion = remoteInk.version, inkDirty = fresh.inkDirty || localHadMore, lastSyncedAt = System.currentTimeMillis())
        repo.update(updated)
        log("Merged annotations for ${n.title}" + if (localHadMore) " (local additions will upload)" else "")
        updated
    }

    // ------------------------------------------------------------------ sync

    suspend fun sync(force: Boolean = false): SyncResult {
        if (!mutex.tryLock()) return SyncResult(true, "Sync already running")
        try {
            val providers = cloud.signedInProviders()
            if (providers.isEmpty()) { _status.value = SyncStatus.Idle; return SyncResult(false, "Not signed in") }
            progress("Syncing…")
            val result = try {
                withContext(Dispatchers.IO) { doSync(force) }
            } catch (e: NotSignedInException) {
                SyncResult(false, e.message ?: "Signed out")
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
                SyncResult(false, e.message ?: e.javaClass.simpleName)
            }
            prefs.setLastSync(System.currentTimeMillis(), if (result.ok) result.message else "Failed: ${result.message}")
            _status.value = if (result.ok) SyncStatus.Idle else SyncStatus.Failed(result.message)
            log(if (result.ok) "Done: ${result.message}" else "Failed: ${result.message}")
            return result
        } finally { mutex.unlock() }
    }

    private suspend fun doSync(force: Boolean): SyncResult {
        var uploaded = 0; var pulled = 0; var later = false
        val settings = prefs.current()
        val failures = ArrayList<String>()

        // 1. Push.
        for (note in repo.pending()) {
            if (!cloud.isSignedIn(note.provider)) continue
            val provider = cloud.providers.getValue(note.provider)
            try {
                var n = note
                if (n.inkDirty) { progress("Uploading annotations for ${n.title}"); n = pushSidecar(provider, n); uploaded++ }
                if (n.writeBackPending && settings.writeBack != WriteBackMode.OFF) {
                    val idle = System.currentTimeMillis() - n.modifiedAt > WRITE_BACK_IDLE_MS
                    if (force || idle || n.id !in openNotes) {
                        progress("Writing annotated PDF for ${n.title}")
                        writeBack(provider, n, settings.writeBack); uploaded++
                    } else later = true
                } else if (n.writeBackPending) {
                    repo.update(n.copy(writeBackPending = false))
                }
                repo.setSyncError(n.id, null)
            } catch (e: Exception) {
                Log.w(TAG, "Push failed for ${note.title}", e)
                log("Upload of ${note.title} failed: ${e.message}")
                repo.setSyncError(note.id, e.message)
                failures += note.title
            }
        }

        // 2. Pull for recently opened notes (annotations made on other devices, replaced PDFs).
        // After the update that introduced the .ink folder, visit every note once so all
        // sidecars move; afterwards only recent notes are checked.
        val migrateAll = !settings.inkFolderMigrated
        val toPull = if (migrateAll) repo.all() else repo.recent(PULL_RECENT)
        var migrationComplete = migrateAll
        for (note in toPull) {
            if (!cloud.isSignedIn(note.provider)) continue
            val provider = cloud.providers.getValue(note.provider)
            try {
                progress("Checking ${note.title}")
                if (pullNote(provider, note)) pulled++
            } catch (e: Exception) {
                log("Check of ${note.title} failed: ${e.message}")
                migrationComplete = false
            }
        }
        if (migrateAll) {
            // Uploads of moved sidecars happen in the push step; run it once more now.
            for (note in repo.pending().filter { it.legacyInkRemoteId != null && cloud.isSignedIn(it.provider) }) {
                try { pushSidecar(cloud.providers.getValue(note.provider), note); uploaded++ } catch (e: Exception) { migrationComplete = false; log("Move of ${note.title} failed: ${e.message}") }
            }
            if (migrationComplete) { prefs.setInkFolderMigrated(true); log("All annotation files now live in ${CloudProvider.INK_FOLDER}/ folders") }
        }

        val msg = when {
            failures.isNotEmpty() -> "Could not upload ${failures.joinToString(", ")}"
            uploaded == 0 && pulled == 0 -> "Up to date"
            else -> listOfNotNull(uploaded.takeIf { it > 0 }?.let { "$it uploaded" }, pulled.takeIf { it > 0 }?.let { "$it updated" }).joinToString(", ")
        }
        return SyncResult(failures.isEmpty(), msg, uploaded, pulled, later)
    }

    /** Uploads the sidecar; on a conflict merges the remote copy first and retries. */
    private suspend fun pushSidecar(provider: CloudProvider, initial: NoteEntity): NoteEntity {
        var note = initial
        val docParent = note.parentId ?: throw CloudException(400, null, "Unknown folder for ${note.title}")
        val parent = inkFolder(provider, docParent, create = true) ?: throw CloudException(500, null, "Could not create the ${CloudProvider.INK_FOLDER} folder")
        var attempts = 0
        while (true) {
            attempts++
            if (!provider.supportsConditionalUpload && note.inkRemoteId != null) {
                // No If-Match on this drive: look before we leap.
                val remote = provider.getFile(note.inkRemoteId!!)
                if (remote == null) note = note.copy(inkRemoteId = null, inkVersion = null)
                else if (remote.version != note.inkVersion) {
                    val doc = runCatching { InkJson.decode(provider.downloadText(remote)) }.getOrNull()
                    if (doc != null) repo.mergeRemoteInk(note.id, doc)
                    note = note.copy(inkVersion = remote.version)
                }
            }
            val doc = repo.loadInk(note.id)
            val bytes = InkJson.encode(doc).toByteArray()
            try {
                val eTag = if (provider.supportsConditionalUpload) remoteETag(provider, note) else null
                val item = provider.uploadBytes(parent, note.inkFileName, bytes, "application/json", note.inkRemoteId, eTag)
                val after = repo.loadInk(note.id)
                val stillSame = after.contentSignature() == doc.contentSignature()
                val fresh = repo.get(note.id) ?: return note
                note = fresh.copy(inkRemoteId = item.id, inkVersion = item.version, inkDirty = !stillSame, lastSyncedAt = System.currentTimeMillis())
                repo.update(note)
                log("Uploaded annotations for ${note.title}")
                // The move is complete only now: the old copy next to the document can go.
                note.legacyInkRemoteId?.let { legacyId ->
                    if (legacyId != item.id) {
                        runCatching { provider.delete(legacyId) }
                            .onSuccess { log("Removed old annotation file next to ${note.title}") }
                            .onFailure { log("Could not remove old annotation file for ${note.title}: ${it.message}") }
                    }
                    note = note.copy(legacyInkRemoteId = null)
                    repo.update(note)
                }
                return note
            } catch (e: CloudException) {
                if (e.isNotFound && attempts < 3 && note.inkRemoteId == null) {
                    // The .ink folder we cached may have been deleted: look it up again.
                    inkFolders.remove("${provider.kind}:$docParent")
                    inkFolder(provider, docParent, create = true)
                    continue
                }
                if ((e.isPreconditionFailed || e.isNotFound) && attempts < 3 && note.inkRemoteId != null) {
                    if (e.isNotFound) note = note.copy(inkRemoteId = null, inkVersion = null)
                    else {
                        val remote = provider.getFile(note.inkRemoteId!!)
                        if (remote != null) {
                            runCatching { InkJson.decode(provider.downloadText(remote)) }.getOrNull()?.let { repo.mergeRemoteInk(note.id, it) }
                            note = note.copy(inkVersion = remote.version)
                        } else note = note.copy(inkRemoteId = null, inkVersion = null)
                    }
                    repo.update(note)
                    continue
                }
                throw e
            }
        }
    }

    private suspend fun remoteETag(provider: CloudProvider, note: NoteEntity): String? {
        val id = note.inkRemoteId ?: return null
        return provider.getFile(id)?.eTag
    }

    /** Rebuilds the annotated PDF and uploads it (in place or as a sibling). */
    private suspend fun writeBack(provider: CloudProvider, note: NoteEntity, mode: WriteBackMode) {
        val parent = note.parentId ?: return
        val ink = repo.loadInk(note.id)
        val out = File(cacheDir, "writeback/${note.id}.pdf").apply { parentFile?.mkdirs() }
        exporter.exportTo(repo.pdfFile(note.id), ink, out)
        try {
            // Annotations cannot be written into a PowerPoint/Word file: converted notes always get a sibling PDF.
            val effective = if (note.converted && mode == WriteBackMode.IN_PLACE) WriteBackMode.SIBLING else mode
            when (effective) {
                WriteBackMode.IN_PLACE -> {
                    // Don't clobber a PDF someone replaced since we downloaded it; the pull step handles that.
                    val remote = provider.getFile(note.remoteId)
                    if (remote != null && remote.version != note.pdfVersion) { log("${note.title} changed on the drive; skipping write-back until it is refreshed"); return }
                    val item = provider.upload(parent, note.fileName, out, "application/pdf", note.remoteId, remote?.eTag)
                    repo.update((repo.get(note.id) ?: note).copy(pdfVersion = item.version, writeBackPending = false, lastSyncedAt = System.currentTimeMillis()))
                }
                WriteBackMode.SIBLING -> {
                    val name = note.title + ".annotated.pdf"
                    val existing = provider.findChild(parent, name)
                    provider.upload(parent, name, out, "application/pdf", existing?.id, null)
                    repo.update((repo.get(note.id) ?: note).copy(writeBackPending = false, lastSyncedAt = System.currentTimeMillis()))
                }
                WriteBackMode.OFF -> Unit
            }
            log("Wrote annotated PDF for ${note.title}")
        } finally { out.delete() }
    }

    /** Pulls a replaced PDF or new remote annotations. Returns true when anything changed. */
    private suspend fun pullNote(provider: CloudProvider, note: NoteEntity): Boolean {
        var changed = false
        val remote = provider.getFile(note.remoteId)
        if (remote == null) {
            if (note.syncError == null) repo.setSyncError(note.id, "No longer on ${note.provider.label}")
            return false
        }
        if (remote.version != note.pdfVersion && remote.version != null) changed = refreshPdf(provider, note)
        var n = repo.get(note.id) ?: note
        val before = n.inkVersion
        n = pullSidecar(provider, n)
        return changed || n.inkVersion != before
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val WRITE_BACK_IDLE_MS = 45_000L
        const val PULL_RECENT = 12
    }
}
