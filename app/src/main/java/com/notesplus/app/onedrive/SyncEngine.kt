package com.notesplus.app.onedrive

import android.util.Log
import com.notesplus.app.data.NoteRepository
import com.notesplus.app.data.Prefs
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.NoteKind
import com.notesplus.app.export.PdfExporter
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.InkJson
import com.notesplus.app.onedrive.auth.AuthManager
import com.notesplus.app.onedrive.auth.NotSignedInException
import com.notesplus.app.util.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLDecoder

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Running(val message: String) : SyncStatus()
    data class Failed(val message: String) : SyncStatus()
}

data class SyncResult(val ok: Boolean, val message: String, val downloaded: Int = 0, val uploaded: Int = 0)

/**
 * Two-way sync between the local library and a OneDrive folder.
 *
 * 1. Pull: a delta query lists everything that changed in the folder since the last
 *    sync. New/changed PDFs are downloaded; changed ".ink.json" sidecars are merged
 *    into the local annotations (union of strokes minus tombstones, so nothing is lost).
 * 2. Push: queued deletions are applied, then every note with local changes is
 *    uploaded. Ink uploads are conditional on the remote eTag; a 412 means somebody
 *    else wrote first, so we merge their copy in and upload again.
 */
class SyncEngine(
    private val repo: NoteRepository,
    private val prefs: Prefs,
    private val auth: AuthManager,
    private val graph: GraphClient,
    private val exporter: PdfExporter
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _log.value = (_log.value + "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $msg").takeLast(80)
    }

    private fun progress(msg: String) { _status.value = SyncStatus.Running(msg) }

    val isRunning: Boolean get() = mutex.isLocked

    suspend fun sync(): SyncResult {
        if (!mutex.tryLock()) return SyncResult(true, "Sync already running")
        try {
            if (!auth.isSignedIn) {
                _status.value = SyncStatus.Idle
                return SyncResult(false, "Not signed in")
            }
            progress("Connecting…")
            val result = try {
                doSync()
            } catch (e: NotSignedInException) {
                SyncResult(false, "Signed out. Please sign in to OneDrive again.")
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
                SyncResult(false, e.message ?: e.javaClass.simpleName)
            }
            prefs.setLastSync(System.currentTimeMillis(), if (result.ok) result.message else "Failed: ${result.message}")
            _status.value = if (result.ok) SyncStatus.Idle else SyncStatus.Failed(result.message)
            log(if (result.ok) "Done: ${result.message}" else "Failed: ${result.message}")
            return result
        } finally {
            mutex.unlock()
        }
    }

    private class Ctx(val folderId: String, val folderPath: String) {
        /** folder id -> relative dir inside the sync folder */
        val dirCache = HashMap<String, String>()
    }

    private suspend fun doSync(): SyncResult {
        val settings = prefs.current()
        // Resolve (or create) the sync folder.
        val folderId = settings.syncFolderId ?: run {
            progress("Preparing folder ${settings.syncFolderPath}…")
            val folder = graph.ensureFolderPath(settings.syncFolderPath)
            prefs.setSyncFolder(settings.syncFolderPath, folder.id)
            log("Using OneDrive folder ${settings.syncFolderPath}")
            folder.id
        }
        val ctx = Ctx(folderId, settings.syncFolderPath)
        ctx.dirCache[folderId] = ""

        var downloaded = 0
        var uploaded = 0

        // ---------------------------------------------------------------- pull
        progress("Checking OneDrive for changes…")
        val delta = graph.delta(folderId, prefs.current().deltaLink)
        val items = delta.items.filter { it.id != folderId }
        // Folders first so the directory cache is complete, then PDFs, then sidecars.
        for (f in items.filter { it.isFolder && !it.deleted }) {
            ctx.dirCache[f.id] = NoteEntity.joinPath(Files.sanitizeDir(relativeDirOf(ctx, f)), f.name)
        }
        val deletedItems = items.filter { it.deleted }
        val pdfs = items.filter { !it.deleted && !it.isFolder && it.name.endsWith(".pdf", true) }
        val sidecars = items.filter { !it.deleted && !it.isFolder && it.name.endsWith(".ink.json", true) }

        for (d in deletedItems) handleRemoteDelete(d)
        for ((i, item) in pdfs.withIndex()) {
            progress("Downloading ${item.name} (${i + 1}/${pdfs.size})")
            if (handleRemotePdf(ctx, item)) downloaded++
        }
        for (item in sidecars) {
            progress("Merging annotations for ${item.name}")
            if (handleRemoteSidecar(ctx, item)) downloaded++
        }
        prefs.setDeltaLink(delta.deltaLink)

        // ---------------------------------------------------------------- push
        for (pd in repo.pendingDeletes()) {
            progress("Deleting ${pd.path} on OneDrive")
            try { graph.delete(pd.remoteId); log("Deleted ${pd.path}") } catch (e: GraphException) { if (!e.isNotFound) throw e }
            repo.clearPendingDelete(pd)
        }
        val dirty = repo.dirtyNotes()
        for ((i, note) in dirty.withIndex()) {
            progress("Uploading ${note.title} (${i + 1}/${dirty.size})")
            try {
                if (pushNote(ctx, note)) uploaded++
                repo.setSyncError(note.id, null)
            } catch (e: GraphException) {
                log("Upload of ${note.title} failed: ${e.message}")
                repo.setSyncError(note.id, e.message)
            }
        }

        val msg = when {
            downloaded == 0 && uploaded == 0 -> "Up to date"
            else -> listOfNotNull(
                downloaded.takeIf { it > 0 }?.let { "$it downloaded" },
                uploaded.takeIf { it > 0 }?.let { "$it uploaded" }
            ).joinToString(", ")
        }
        return SyncResult(true, msg, downloaded, uploaded)
    }

    /** Relative directory (inside the sync folder) that contains [item]. */
    private suspend fun relativeDirOf(ctx: Ctx, item: DriveItem): String {
        val parentId = item.parentId ?: return ""
        ctx.dirCache[parentId]?.let { return it }
        // Try the textual path first: "/drive/root:/NotesPlus/Week 1".
        val path = item.parentPath?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        val rootPrefix = "/drive/root:" + ctx.folderPath.trimEnd('/')
        if (path != null && path.startsWith(rootPrefix, ignoreCase = true)) {
            val rel = path.substring(rootPrefix.length).trim('/')
            ctx.dirCache[parentId] = rel
            return rel
        }
        // Fall back to walking up the parent chain.
        val chain = ArrayList<String>()
        var cur = parentId
        var depth = 0
        while (cur != ctx.folderId && depth++ < 32) {
            val cached = ctx.dirCache[cur]
            if (cached != null) { chain.add(0, cached); break }
            val parent = graph.item(cur)
            chain.add(0, parent.name)
            cur = parent.parentId ?: break
        }
        val rel = chain.filter { it.isNotEmpty() }.joinToString("/")
        ctx.dirCache[parentId] = rel
        return rel
    }

    private suspend fun handleRemoteDelete(item: DriveItem) {
        repo.byPdfRemoteId(item.id)?.let { note ->
            if (note.inkDirty || note.pdfDirty) {
                // Local edits exist: re-upload rather than silently dropping work.
                log("${note.title} was deleted on OneDrive but has local changes; re-uploading")
                repo.update(note.copy(pdfRemoteId = null, pdfCTag = null, pdfETag = null, pdfDirty = true, inkRemoteId = null, inkETag = null, inkDirty = true))
            } else {
                log("${note.title} was deleted on OneDrive; removing locally")
                repo.removeLocalOnly(note.id)
            }
            return
        }
        repo.byInkRemoteId(item.id)?.let { note ->
            if (note.kind == NoteKind.INK && !note.inkDirty) {
                log("${note.title} was deleted on OneDrive; removing locally")
                repo.removeLocalOnly(note.id)
            } else {
                // Sidecar removed (or notebook with local edits): keep annotations and re-upload.
                repo.update(note.copy(inkRemoteId = null, inkETag = null, inkDirty = true))
            }
        }
    }

    /** Returns true when the PDF was (re)downloaded. */
    private suspend fun handleRemotePdf(ctx: Ctx, item: DriveItem): Boolean {
        val dir = relativeDirOf(ctx, item)
        val existing = repo.byPdfRemoteId(item.id) ?: repo.byPath(dir, item.name)
        if (existing != null) {
            val unchanged = existing.pdfRemoteId == item.id && existing.pdfCTag != null && existing.pdfCTag == item.cTag
            if (unchanged) return false
            if (existing.pdfDirty && existing.pdfRemoteId == null) {
                // We imported a file with the same name locally before ever syncing. Keep both:
                // rename our local copy so the upload does not overwrite theirs.
                val renamed = uniqueLocalName(dir, item.name)
                repo.update(existing.copy(fileName = renamed, title = Files.stripExtension(renamed, ".pdf")))
                return handleRemotePdf(ctx, item)
            }
            log("Downloading updated ${item.name}")
            graph.download(item, repo.pdfFile(existing.id))
            val pages = repo.countPdfPages(repo.pdfFile(existing.id)).coerceAtLeast(0)
            repo.update(
                existing.copy(
                    relativeDir = dir, fileName = item.name, title = Files.stripExtension(item.name, ".pdf"),
                    pageCount = pages, pdfRemoteId = item.id, pdfCTag = item.cTag, pdfETag = item.eTag, pdfDirty = false,
                    modifiedAt = maxOf(existing.modifiedAt, item.lastModified), lastSyncedAt = System.currentTimeMillis()
                )
            )
            return true
        }
        log("Downloading new ${item.name}")
        val tmpId = java.util.UUID.randomUUID().toString()
        // Download into the final note directory: registerRemotePdf allocates the id for us,
        // so download to a temp dir and move.
        val tmp = File(repo.noteDir(tmpId), "document.pdf")
        graph.download(item, tmp)
        val note = repo.registerRemotePdfFrom(tmp, dir, item.name, item.id, item.cTag, item.eTag, item.lastModified)
        repo.noteDir(tmpId).deleteRecursively()
        log("Added ${note.title}")
        return true
    }

    private suspend fun uniqueLocalName(dir: String, wanted: String): String {
        val base = Files.stripExtension(wanted, ".pdf")
        var i = 2
        while (true) {
            val c = "$base (local $i).pdf"
            if (repo.byPath(dir, c) == null) return c
            i++
        }
    }

    /** Returns true when remote annotations were merged in. */
    private suspend fun handleRemoteSidecar(ctx: Ctx, item: DriveItem): Boolean {
        val dir = relativeDirOf(ctx, item)
        val baseName = Files.stripExtension(item.name, ".ink.json")
        val isPdfSidecar = baseName.endsWith(".pdf", ignoreCase = true)
        var note = repo.byInkRemoteId(item.id)
            ?: if (isPdfSidecar) repo.byPath(dir, baseName) else repo.byPath(dir, item.name)
        if (note == null) {
            if (isPdfSidecar) {
                // Sidecar for a PDF we don't have (yet). It will be picked up when the PDF arrives
                // (the PDF handler runs before sidecars within a delta page; across pages we
                // simply try again next sync).
                val pdfItem = graph.children(item.parentId ?: ctx.folderId).firstOrNull { it.name.equals(baseName, true) }
                if (pdfItem == null) { log("Skipping orphan annotations ${item.name}"); return false }
                handleRemotePdf(ctx, pdfItem)
                note = repo.byPath(dir, baseName) ?: return false
            } else {
                // A standalone notebook created on another device.
                val text = graph.downloadText(item)
                val doc = runCatching { InkJson.decode(text) }.getOrElse { log("Ignoring unreadable ${item.name}"); return false }
                val created = repo.registerRemoteNotebook(dir, item.name, doc, item.id, item.eTag, item.lastModified)
                log("Added notebook ${created.title}")
                return true
            }
        }
        if (note.inkRemoteId == item.id && note.inkETag != null && note.inkETag == item.eTag) return false
        val text = graph.downloadText(item)
        val remoteDoc = runCatching { InkJson.decode(text) }.getOrElse { log("Ignoring unreadable ${item.name}"); return false }
        val (_, localHadMore) = repo.mergeRemoteInk(note.id, remoteDoc)
        val fresh = repo.get(note.id) ?: return true
        repo.update(fresh.copy(inkRemoteId = item.id, inkETag = item.eTag, inkDirty = fresh.inkDirty || localHadMore, lastSyncedAt = System.currentTimeMillis()))
        log("Merged annotations for ${note.title}" + if (localHadMore) " (local additions will be uploaded)" else "")
        return true
    }

    /** Uploads whatever is dirty on [note]. Returns true when anything was uploaded. */
    private suspend fun pushNote(ctx: Ctx, initial: NoteEntity): Boolean {
        var note = initial
        var did = false
        val parentId = graph.ensureSubfolder(ctx.folderId, note.relativeDir)

        if (note.pdfDirty && note.kind == NoteKind.PDF) {
            val file = repo.pdfFile(note.id)
            if (file.exists()) {
                val item = graph.upload(parentId, note.fileName, file, note.pdfRemoteId, null)
                note = note.copy(pdfRemoteId = item.id, pdfCTag = item.cTag, pdfETag = item.eTag, pdfDirty = false, lastSyncedAt = System.currentTimeMillis())
                repo.update(note)
                log("Uploaded ${note.fileName}")
                did = true
            } else {
                note = note.copy(pdfDirty = false); repo.update(note)
            }
        }

        if (note.inkDirty) {
            var attempts = 0
            while (true) {
                attempts++
                val doc = repo.loadInk(note.id)
                val bytes = InkJson.encode(doc).toByteArray()
                try {
                    val item = graph.uploadSmall(parentId, note.inkFileName, bytes, note.inkRemoteId, note.inkETag)
                    // Mark clean only if the editor did not write again while we were uploading.
                    val after = repo.loadInk(note.id)
                    val stillSame = after.contentSignature() == doc.contentSignature()
                    val fresh = repo.get(note.id) ?: return did
                    note = fresh.copy(inkRemoteId = item.id, inkETag = item.eTag, inkDirty = !stillSame, lastSyncedAt = System.currentTimeMillis())
                    repo.update(note)
                    log("Uploaded annotations for ${note.title}")
                    did = true
                    break
                } catch (e: GraphException) {
                    if ((e.isPreconditionFailed || e.isNotFound) && attempts < 3 && note.inkRemoteId != null) {
                        // Remote changed under us (412) or was deleted (404): reconcile and retry.
                        if (e.isNotFound) {
                            note = note.copy(inkRemoteId = null, inkETag = null)
                        } else {
                            val remote = graph.item(note.inkRemoteId!!)
                            val remoteDoc = runCatching { InkJson.decode(graph.downloadText(remote)) }.getOrNull()
                            if (remoteDoc != null) repo.mergeRemoteInk(note.id, remoteDoc)
                            note = note.copy(inkETag = remote.eTag)
                        }
                        repo.update(note)
                        continue
                    }
                    throw e
                }
            }
        }

        if (did && prefs.current().exportAnnotatedPdfOnSync) {
            runCatching {
                val out = exporter.export(note)
                val name = Files.stripExtension(note.fileName, ".pdf", ".ink.json") + ".annotated.pdf"
                graph.upload(parentId, name, out, null, null)
                log("Uploaded $name")
            }.onFailure { log("Annotated PDF export failed: ${it.message}") }
        }
        return did
    }

    private companion object { const val TAG = "SyncEngine" }
}
