package com.notesplus.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE provider = :provider AND remoteId = :remoteId LIMIT 1")
    suspend fun byRemote(provider: Provider, remoteId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE inkDirty = 1 OR writeBackPending = 1")
    suspend fun pending(): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE inkDirty = 1 OR writeBackPending = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT * FROM notes ORDER BY lastOpenedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notes WHERE provider = :provider")
    suspend fun deleteAllFor(provider: Provider)

    @Query("UPDATE notes SET inkDirty = 1, writeBackPending = 1, modifiedAt = :now WHERE id = :id")
    suspend fun markInkDirty(id: String, now: Long)

    @Query("UPDATE notes SET syncError = :error WHERE id = :id")
    suspend fun setSyncError(id: String, error: String?)
}

@Dao
interface CloudDao {
    @Query("SELECT * FROM cloud_items WHERE provider = :provider AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) ORDER BY sortKey, isFolder DESC, name COLLATE NOCASE")
    fun observeChildren(provider: Provider, parentId: String?): Flow<List<CloudItemEntity>>

    @Query("SELECT * FROM cloud_items WHERE provider = :provider AND id = :id")
    suspend fun get(provider: Provider, id: String): CloudItemEntity?

    @Query("SELECT * FROM cloud_items WHERE provider = :provider AND id = :id")
    fun observe(provider: Provider, id: String): Flow<CloudItemEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CloudItemEntity>)

    @Query("DELETE FROM cloud_items WHERE provider = :provider AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)")
    suspend fun deleteChildren(provider: Provider, parentId: String?)

    @Query("DELETE FROM cloud_items WHERE provider = :provider")
    suspend fun deleteAllFor(provider: Provider)

    @Query("SELECT * FROM folder_state WHERE provider = :provider AND folderId = :folderId")
    suspend fun folderState(provider: Provider, folderId: String): FolderStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFolderState(state: FolderStateEntity)

    @Query("DELETE FROM folder_state WHERE provider = :provider")
    suspend fun deleteFolderStatesFor(provider: Provider)

    /** Atomically replaces the cached listing of one folder. */
    @Transaction
    suspend fun replaceChildren(provider: Provider, parentId: String?, items: List<CloudItemEntity>) {
        deleteChildren(provider, parentId)
        if (items.isNotEmpty()) upsertAll(items)
        setFolderState(FolderStateEntity(provider, parentId ?: "", System.currentTimeMillis()))
    }

    @Query("SELECT COUNT(*) FROM cloud_items WHERE provider = :provider AND parentId = :parentId AND isFolder = 0")
    suspend fun pdfCount(provider: Provider, parentId: String): Int
}
