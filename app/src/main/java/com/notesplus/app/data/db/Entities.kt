package com.notesplus.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class NoteKind { PDF, INK }

/**
 * One note in the library. A note is either an imported/synced PDF with an ink
 * sidecar, or a standalone handwritten notebook (ink only).
 *
 * Remote bookkeeping (ids / eTags) is what lets the sync engine know whether a file
 * changed on OneDrive since we last saw it; the *Dirty flags record local changes
 * that still need to be uploaded.
 */
@Entity(
    tableName = "notes",
    indices = [Index("pdfRemoteId"), Index("inkRemoteId"), Index(value = ["relativeDir", "fileName"], unique = true)]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: NoteKind,
    /** Folder path inside the sync folder, "" for the root. Uses '/' separators, no leading slash. */
    val relativeDir: String = "",
    /** Primary remote file name: "X.pdf" for PDF notes, "X.ink.json" for notebooks. */
    val fileName: String,
    val pageCount: Int = 0,
    val createdAt: Long,
    val modifiedAt: Long,

    val pdfRemoteId: String? = null,
    val pdfCTag: String? = null,
    val pdfETag: String? = null,
    val pdfDirty: Boolean = false,

    val inkRemoteId: String? = null,
    val inkETag: String? = null,
    val inkDirty: Boolean = false,

    val lastSyncedAt: Long? = null,
    val syncError: String? = null
) {
    val inkFileName: String get() = if (kind == NoteKind.PDF) "$fileName.ink.json" else fileName
    val remotePdfPath: String get() = joinPath(relativeDir, fileName)
    val remoteInkPath: String get() = joinPath(relativeDir, inkFileName)
    val needsUpload: Boolean get() = pdfDirty || inkDirty

    companion object {
        fun joinPath(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
    }
}

/** A remote item the user deleted locally; removed from OneDrive on the next sync. */
@Entity(tableName = "pending_deletes")
data class PendingDeleteEntity(
    @PrimaryKey val remoteId: String,
    val path: String,
    val queuedAt: Long
)
