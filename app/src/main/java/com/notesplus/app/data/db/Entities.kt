package com.notesplus.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cloud storage providers the app can browse. */
enum class Provider(val label: String) { ONEDRIVE("OneDrive"), GOOGLE_DRIVE("Google Drive") }

/**
 * A folder or PDF listed from a cloud drive, cached so the tree can be browsed instantly
 * and offline. [parentId] is null for the top-level entries of a provider.
 */
@Entity(
    tableName = "cloud_items",
    primaryKeys = ["provider", "id"],
    indices = [Index(value = ["provider", "parentId"])]
)
data class CloudItemEntity(
    val provider: Provider,
    val id: String,
    val parentId: String?,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modifiedAt: Long,
    /** Content version marker: OneDrive cTag, Google Drive md5Checksum/version. */
    val version: String?,
    /** Ordering hint so virtual roots ("My Drive", "Shared with me") come first. */
    val sortKey: Int = 0,
    val fetchedAt: Long
)

/** Remembers when a folder was last listed, so stale caches refresh in the background. */
@Entity(tableName = "folder_state", primaryKeys = ["provider", "folderId"])
data class FolderStateEntity(
    val provider: Provider,
    /** "" for the provider root. */
    val folderId: String,
    val listedAt: Long
)

/**
 * A PDF the user opened: downloaded locally together with its annotations.
 * Remote bookkeeping tells the sync engine whether either side changed.
 */
@Entity(tableName = "notes", indices = [Index(value = ["provider", "remoteId"], unique = true)])
data class NoteEntity(
    @PrimaryKey val id: String,
    val provider: Provider,
    /** Id of the PDF on the drive. */
    val remoteId: String,
    /** Folder containing the PDF (where the sidecar is written). */
    val parentId: String?,
    /** Source file name, e.g. "Lecture 3.pdf" or "Week 2 slides.pptx". */
    val fileName: String,
    /** True when the local PDF was converted from an Office / Google document on the drive. */
    val converted: Boolean = false,
    /** Human readable location, e.g. "My Drive / COMP1234 / Week 1". */
    val pathLabel: String,
    val pageCount: Int = 0,
    val sizeBytes: Long = 0L,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long,

    /** Version of the PDF we have locally. */
    val pdfVersion: String? = null,

    val inkRemoteId: String? = null,
    /** Version marker of the sidecar last synced. */
    val inkVersion: String? = null,
    val inkDirty: Boolean = false,
    /** The annotated PDF on the drive is behind the ink and must be rewritten. */
    val writeBackPending: Boolean = false,

    val lastSyncedAt: Long? = null,
    val syncError: String? = null
) {
    val title: String get() = fileName.substringBeforeLast('.', fileName)
    val inkFileName: String get() = "$fileName.ink.json"
}
