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
    suspend fun prepareNote(item: CloudItemEntity, onProgress: (String) -> Unit = {}): NoteEntity = withContext(Dispatchers.IO) {
        val provider = cloud.providers.getValue(item.provider)
        val existing = repo.byRemote(item.provider, item.id)
        val pathLabel = cloud.pathLabel(item.provider, item.parentId)
        val remote = runCatching { provider.getFile(item.id) }.getOrNull()
        val id = existing?.id ?: UUID.randomUUID().toString()
        val pdf = repo.pdfFile(id)

        val needDownload = existing == null || !pdf.exists() || (remote != null && remote.version != existing.pdfVersion)
        if (needDownload) {
            if (remote == null) {
                if (existing != null && pdf.exists()) return@withContext existing
                throw CloudException(404, null, "This file is no longer on ${item.provider.label}")
            }
            val converting = !remote.isPdf
            onProgress(if (converting) "Converting ${item.name} to PDF…" else "Downloading ${item.name}…")
            provider.downloadAsPdf(remote, pdf)
            onProgress("Preparing pages…")
            if (!converting) runCatching { exporter.stripLayer(pdf) }.onFailure { log("Could not inspect ${item.name} for an annotation layer: ${it.message}") }
        }
        repo.ensureInkFile(id)
        val pages = if (needDownload || existing?.pageCount == 0) repo.countPdfPages(pdf).coerceAtLeast(0) else existing.pageCount
        val now = System.currentTimeMillis()
        var note = (existing ?: NoteEntity(
            id = id, provider = item.provider, remoteId = item.id, parentId = item.parentId, fileName = item.name,
            pathLabel = pathLabel, createdAt = now, modifiedAt = now, lastOpenedAt = now
        )).copy(
            fileName = remote?.name ?: item.name, parentId = remote?.parentId ?: item.parentId, pathLabel = pathLabel,
            pageCount = pages, sizeBytes = remote?.size ?: item.size, pdfVersion = remote?.version ?: existing?.pdfVersion,
            converted = remote?.let { !it.isPdf } ?: existing?.converted ?: false,
            lastOpenedAt = now, syncError = null
        )
        repo.upsert(note)
        // Pick up annotations made elsewhere.
        onProgress("Checking for annotations…")
        note = runCatching { pullSidecar(provider, note) }.getOrElse { log("Could not check annotations for ${note.title}: ${it.message}"); note }
        note
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
    private suspend fun pullSidecar(provider: CloudProvider, note: NoteEntity): NoteEntity {
        val parent = note.parentId ?: return note
        var n = note

        // 1. Legacy sidecar next to the document (layout used before .ink).
        val legacy = provider.findChild(parent, n.inkFileName)?.takeIf { !it.isFolder }
        if (legacy != null) {
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
        val folderId = inkFolder(provider, parent, create = false) ?: return n
        val remoteInk = provider.findChild(folderId, n.inkFileName)?.takeIf { !it.isFolder } ?: return n
        if (remoteInk.id == n.inkRemoteId && remoteInk.version != null && remoteInk.version == n.inkVersion) return n
        val text = provider.downloadText(remoteInk)
        val doc = runCatching { InkJson.decode(text) }.getOrElse { log("Ignoring unreadable ${remoteInk.name}"); return n }
        val (_, localHadMore) = repo.mergeRemoteInk(n.id, doc)
        val fresh = repo.get(n.id) ?: n
        val updated = fresh.copy(inkRemoteId = remoteInk.id, inkVersion = remoteInk.version, inkDirty = fresh.inkDirty || localHadMore, lastSyncedAt = System.currentTimeMillis())
        repo.update(updated)
        log("Merged annotations for ${n.title}" + if (localHadMore) " (local additions will upload)" else "")
        return updated
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
        var n = note
        if (remote.version != n.pdfVersion && remote.version != null) {
            log("${note.title} changed on the drive; downloading")
            provider.downloadAsPdf(remote, repo.pdfFile(n.id))
            if (remote.isPdf) runCatching { exporter.stripLayer(repo.pdfFile(n.id)) }
            repo.thumbnailFile(n.id).delete()
            n = n.copy(pdfVersion = remote.version, fileName = remote.name, pageCount = repo.countPdfPages(repo.pdfFile(n.id)).coerceAtLeast(0), sizeBytes = remote.size)
            repo.update(n)
            changed = true
        }
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
