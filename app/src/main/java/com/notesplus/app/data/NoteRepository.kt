package com.notesplus.app.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.notesplus.app.data.db.NoteDao
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.Provider
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.InkJson
import com.notesplus.app.ink.InkMerge
import com.notesplus.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Local side of a note: metadata (Room) and files on disk.
 *
 * Layout: filesDir/library/<noteId>/document.pdf (pristine original) and ink.json.
 * Every ink write goes through a per-note mutex so the editor's autosave and the sync
 * engine's remote merge never clobber each other.
 */
class NoteRepository(
    private val context: Context,
    private val dao: NoteDao,
    /** Invoked after any local change that needs to reach the drive. */
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
    suspend fun byRemote(provider: Provider, remoteId: String): NoteEntity? = dao.byRemote(provider, remoteId)
    suspend fun pending(): List<NoteEntity> = dao.pending()
    suspend fun recent(limit: Int): List<NoteEntity> = dao.recent(limit)
    suspend fun upsert(note: NoteEntity) = dao.upsert(note)
    suspend fun update(note: NoteEntity) = dao.update(note)
    suspend fun setSyncError(id: String, error: String?) = dao.setSyncError(id, error)

    fun noteDir(id: String): File = File(libraryDir, id).apply { mkdirs() }
    fun pdfFile(id: String): File = File(noteDir(id), "document.pdf")
    fun inkFile(id: String): File = File(noteDir(id), "ink.json")
    fun thumbnailFile(id: String): File = File(context.cacheDir, "thumbs/$id.png")

    fun countPdfPages(file: File): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
    } catch (e: Exception) { -1 }

    // ---------------------------------------------------------------- ink

    suspend fun loadInk(id: String): InkDocument = withContext(Dispatchers.IO) { lockFor(id).withLock { readInkLocked(id) } }

    private fun readInkLocked(id: String): InkDocument {
        val f = inkFile(id)
        if (!f.exists()) return InkDocument.emptyForPdf()
        return try { InkJson.decode(f.readText()) } catch (e: Exception) {
            f.copyTo(File(f.parentFile, "ink.corrupt.${System.currentTimeMillis()}.json"), overwrite = true)
            InkDocument.emptyForPdf()
        }
    }

    suspend fun ensureInkFile(id: String) = withContext(Dispatchers.IO) {
        lockFor(id).withLock { if (!inkFile(id).exists()) Files.writeAtomic(inkFile(id), InkJson.encode(InkDocument.emptyForPdf()).toByteArray()) }
    }

    /**
     * Saves the editor's document, merged with whatever is on disk (sync may have written
     * remote strokes meanwhile). Returns the persisted document and schedules an upload.
     */
    suspend fun saveInk(id: String, doc: InkDocument): InkDocument = withContext(Dispatchers.IO) {
        val merged = lockFor(id).withLock {
            val onDisk = readInkLocked(id)
            val result = if (onDisk.contentSignature() == doc.contentSignature()) doc else InkMerge.merge(doc, onDisk)
            Files.writeAtomic(inkFile(id), InkJson.encode(result).toByteArray())
            result
        }
        dao.markInkDirty(id, System.currentTimeMillis())
        thumbnailFile(id).delete()
        onLocalChange()
        merged
    }

    /**
     * Sync engine entry point: merges a remote document into the local file. Returns the
     * merged document and whether the local side had strokes the remote lacked.
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
            dao.get(id)?.let { n -> dao.update(n.copy(modifiedAt = maxOf(n.modifiedAt, merged.modified))) }
            thumbnailFile(id).delete()
            _externalInkChanges.tryEmit(id)
        }
        merged to localHadMore
    }

    /** Removes all strokes (keeps tombstones so the deletion syncs everywhere). */
    suspend fun clearInk(id: String): InkDocument = withContext(Dispatchers.IO) {
        val doc = loadInk(id)
        var cleared = doc
        for (p in doc.pages) cleared = cleared.removeStrokes(p.index, p.strokes.map { it.id })
        saveInk(id, cleared)
    }

    // ---------------------------------------------------------------- lifecycle

    /** Removes the local copy. Annotations already synced stay on the drive. */
    suspend fun removeLocal(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        noteDir(id).deleteRecursively()
        thumbnailFile(id).delete()
    }

    suspend fun removeAllFor(provider: Provider) = withContext(Dispatchers.IO) {
        dao.getAll().filter { it.provider == provider }.forEach { removeLocal(it.id) }
    }

    suspend fun touchOpened(id: String) { dao.get(id)?.let { dao.update(it.copy(lastOpenedAt = System.currentTimeMillis())) } }
}
