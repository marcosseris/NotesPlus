package com.notesplus.app.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.notesplus.app.data.db.NoteDao
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.NoteKind
import com.notesplus.app.data.db.PendingDeleteEntity
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.InkJson
import com.notesplus.app.ink.InkMerge
import com.notesplus.app.ink.PageSize
import com.notesplus.app.ink.PaperStyle
import com.notesplus.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the local library: note metadata (Room) and note files on disk.
 *
 * Layout: filesDir/library/<noteId>/document.pdf and ink.json.
 *
 * Every ink write goes through a per-note mutex so that the editor's autosave and
 * the sync engine's remote merge can never clobber each other.
 */
class NoteRepository(
    private val context: Context,
    private val dao: NoteDao,
    /** Invoked after any local change that needs to reach OneDrive. */
    private val onLocalChange: () -> Unit
) {
    private val libraryDir: File = File(context.filesDir, "library").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(id: String) = locks.getOrPut(id) { Mutex() }

    private val _externalInkChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits a note id whenever the sync engine changed that note's ink on disk. */
    val externalInkChanges = _externalInkChanges.asSharedFlow()

    fun observeNotes(): Flow<List<NoteEntity>> = dao.observeAll()
    fun observeNote(id: String): Flow<NoteEntity?> = dao.observe(id)
    fun observeDirtyCount(): Flow<Int> = dao.observeDirtyCount()
    suspend fun get(id: String): NoteEntity? = dao.get(id)
    suspend fun all(): List<NoteEntity> = dao.getAll()

    fun noteDir(id: String): File = File(libraryDir, id).apply { mkdirs() }
    fun pdfFile(id: String): File = File(noteDir(id), "document.pdf")
    fun inkFile(id: String): File = File(noteDir(id), "ink.json")
    fun thumbnailFile(id: String): File = File(context.cacheDir, "thumbs/$id.png")

    // ---------------------------------------------------------------- creation

    suspend fun createNotebook(title: String, paper: PaperStyle, pageSize: PageSize = PageSize.A4): NoteEntity =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val safeTitle = Files.sanitizeFileName(title)
            val fileName = uniqueFileName("", "$safeTitle.ink.json")
            val doc = InkDocument.newNotebook(pageSize, paper, 1)
            Files.writeAtomic(inkFile(id), InkJson.encode(doc).toByteArray())
            val note = NoteEntity(
                id = id, title = Files.stripExtension(fileName, ".ink.json"), kind = NoteKind.INK,
                relativeDir = "", fileName = fileName, pageCount = 1,
                createdAt = now, modifiedAt = now, inkDirty = true
            )
            dao.upsert(note)
            onLocalChange()
            note
        }

    /** Copies a PDF picked via the system file picker into the library and queues its upload. */
    suspend fun importPdf(uri: Uri, displayName: String?): NoteEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val target = pdfFile(id)
        context.contentResolver.openInputStream(uri)?.use { Files.writeAtomic(target, it) }
            ?: throw IllegalStateException("Cannot open the selected file")
        val pages = countPdfPages(target)
        if (pages <= 0) { target.delete(); throw IllegalArgumentException("That file is not a readable PDF") }
        val rawName = displayName?.takeIf { it.isNotBlank() } ?: "Imported ${now}.pdf"
        var safe = Files.sanitizeFileName(rawName)
        if (!safe.endsWith(".pdf", ignoreCase = true)) safe += ".pdf"
        val fileName = uniqueFileName("", safe)
        Files.writeAtomic(inkFile(id), InkJson.encode(InkDocument.emptyForPdf().copy(modified = now)).toByteArray())
        val note = NoteEntity(
            id = id, title = Files.stripExtension(fileName, ".pdf"), kind = NoteKind.PDF,
            relativeDir = "", fileName = fileName, pageCount = pages,
            createdAt = now, modifiedAt = now, pdfDirty = true, inkDirty = false
        )
        dao.upsert(note)
        onLocalChange()
        note
    }

    /** Registers a PDF downloaded to [downloaded] (moved into the new note's directory). */
    suspend fun registerRemotePdfFrom(
        downloaded: File, relativeDir: String, fileName: String, remoteId: String, cTag: String?, eTag: String?, modified: Long
    ): NoteEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = pdfFile(id)
        if (!downloaded.renameTo(target)) { downloaded.copyTo(target, overwrite = true); downloaded.delete() }
        val pages = countPdfPages(target).coerceAtLeast(0)
        val note = NoteEntity(
            id = id, title = Files.stripExtension(fileName, ".pdf"), kind = NoteKind.PDF,
            relativeDir = relativeDir, fileName = fileName, pageCount = pages,
            createdAt = modified, modifiedAt = modified,
            pdfRemoteId = remoteId, pdfCTag = cTag, pdfETag = eTag, pdfDirty = false,
            lastSyncedAt = System.currentTimeMillis()
        )
        Files.writeAtomic(inkFile(id), InkJson.encode(InkDocument.emptyForPdf()).toByteArray())
        dao.upsert(note)
        note
    }

    /** Registers a standalone notebook found on OneDrive. */
    suspend fun registerRemoteNotebook(
        relativeDir: String, fileName: String, doc: InkDocument, remoteId: String, eTag: String?, modified: Long
    ): NoteEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val normalized = if (doc.pageSize == null) doc.copy(pageSize = PageSize.A4, pageCount = maxOf(1, doc.pageCount)) else doc.copy(pageCount = maxOf(1, doc.pageCount))
        Files.writeAtomic(inkFile(id), InkJson.encode(normalized).toByteArray())
        val note = NoteEntity(
            id = id, title = Files.stripExtension(fileName, ".ink.json"), kind = NoteKind.INK,
            relativeDir = relativeDir, fileName = fileName, pageCount = normalized.pageCount,
            createdAt = modified, modifiedAt = maxOf(modified, doc.modified),
            inkRemoteId = remoteId, inkETag = eTag, inkDirty = false, lastSyncedAt = System.currentTimeMillis()
        )
        dao.upsert(note)
        note
    }

    private suspend fun uniqueFileName(dir: String, wanted: String): String {
        if (dao.byPath(dir, wanted) == null) return wanted
        val dot = wanted.indexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$base ($i)$ext"
            if (dao.byPath(dir, candidate) == null) return candidate
            i++
        }
    }

    fun countPdfPages(file: File): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (e: Exception) { -1 }

    // ---------------------------------------------------------------- ink

    suspend fun loadInk(id: String): InkDocument = withContext(Dispatchers.IO) {
        lockFor(id).withLock { readInkLocked(id) }
    }

    private fun readInkLocked(id: String): InkDocument {
        val f = inkFile(id)
        if (!f.exists()) return InkDocument.emptyForPdf()
        return try { InkJson.decode(f.readText()) } catch (e: Exception) {
            // Keep a copy of anything we can't parse rather than silently losing it.
            f.copyTo(File(f.parentFile, "ink.corrupt.${System.currentTimeMillis()}.json"), overwrite = true)
            InkDocument.emptyForPdf()
        }
    }

    /**
     * Saves the editor's current document. Because sync may have merged remote strokes
     * into the file in the meantime, the on-disk doc is merged with [doc] rather than
     * overwritten, so nothing is ever lost. Returns the document actually persisted.
     */
    suspend fun saveInk(id: String, doc: InkDocument): InkDocument = withContext(Dispatchers.IO) {
        val merged = lockFor(id).withLock {
            val onDisk = readInkLocked(id)
            val result = if (onDisk.contentSignature() == doc.contentSignature()) doc else InkMerge.merge(doc, onDisk)
            Files.writeAtomic(inkFile(id), InkJson.encode(result).toByteArray())
            result
        }
        val pageCount = dao.get(id)?.let { n -> if (n.kind == NoteKind.INK) merged.pageCount else n.pageCount } ?: merged.pageCount
        dao.markInkDirty(id, System.currentTimeMillis(), pageCount)
        onLocalChange()
        merged
    }

    /**
     * Used by the sync engine: merges a remote document into the local file.
     * Returns the merged document and whether the local side held strokes the
     * remote did not (in which case the merge result must be uploaded).
     */
    suspend fun mergeRemoteInk(id: String, remote: InkDocument): Pair<InkDocument, Boolean> = withContext(Dispatchers.IO) {
        val (merged, changedLocally, localHadMore) = lockFor(id).withLock {
            val local = readInkLocked(id)
            val merged = InkMerge.merge(local, remote)
            val changedLocally = merged.contentSignature() != local.contentSignature()
            val localHadMore = merged.contentSignature() != remote.contentSignature()
            if (changedLocally) Files.writeAtomic(inkFile(id), InkJson.encode(merged).toByteArray())
            Triple(merged, changedLocally, localHadMore)
        }
        if (changedLocally) {
            val note = dao.get(id)
            if (note != null) {
                dao.update(note.copy(modifiedAt = maxOf(note.modifiedAt, merged.modified), pageCount = if (note.kind == NoteKind.INK) merged.pageCount else note.pageCount))
            }
            _externalInkChanges.tryEmit(id)
        }
        merged to localHadMore
    }

    suspend fun update(note: NoteEntity) = dao.update(note)

    // ---------------------------------------------------------------- rename / delete

    suspend fun rename(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        val note = dao.get(id) ?: return@withContext
        val safe = Files.sanitizeFileName(newTitle)
        val ext = if (note.kind == NoteKind.PDF) ".pdf" else ".ink.json"
        val fileName = uniqueFileName(note.relativeDir, safe + ext)
        if (fileName == note.fileName) return@withContext
        // A rename is pushed by re-uploading under the new name and deleting the old remote items.
        note.pdfRemoteId?.let { dao.queueDelete(PendingDeleteEntity(it, note.remotePdfPath, System.currentTimeMillis())) }
        note.inkRemoteId?.let { dao.queueDelete(PendingDeleteEntity(it, note.remoteInkPath, System.currentTimeMillis())) }
        dao.update(
            note.copy(
                title = Files.stripExtension(fileName, ext), fileName = fileName, modifiedAt = System.currentTimeMillis(),
                pdfRemoteId = null, pdfCTag = null, pdfETag = null, pdfDirty = note.kind == NoteKind.PDF,
                inkRemoteId = null, inkETag = null, inkDirty = true
            )
        )
        onLocalChange()
    }

    suspend fun delete(id: String, alsoOnOneDrive: Boolean) = withContext(Dispatchers.IO) {
        val note = dao.get(id) ?: return@withContext
        if (alsoOnOneDrive) {
            note.pdfRemoteId?.let { dao.queueDelete(PendingDeleteEntity(it, note.remotePdfPath, System.currentTimeMillis())) }
            note.inkRemoteId?.let { dao.queueDelete(PendingDeleteEntity(it, note.remoteInkPath, System.currentTimeMillis())) }
        }
        dao.delete(id)
        noteDir(id).deleteRecursively()
        thumbnailFile(id).delete()
        if (alsoOnOneDrive) onLocalChange()
    }

    /** Removes local data for a note that disappeared from OneDrive (keeps nothing). */
    suspend fun removeLocalOnly(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        noteDir(id).deleteRecursively()
        thumbnailFile(id).delete()
    }

    suspend fun pendingDeletes() = dao.pendingDeletes()
    suspend fun clearPendingDelete(item: PendingDeleteEntity) = dao.clearPendingDelete(item)
    suspend fun dirtyNotes() = dao.dirty()
    suspend fun byPdfRemoteId(id: String) = dao.byPdfRemoteId(id)
    suspend fun byInkRemoteId(id: String) = dao.byInkRemoteId(id)
    suspend fun byPath(dir: String, name: String) = dao.byPath(dir, name)
    suspend fun setSyncError(id: String, error: String?) = dao.setSyncError(id, error)
}
