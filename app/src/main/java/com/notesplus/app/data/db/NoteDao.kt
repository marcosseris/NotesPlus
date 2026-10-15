package com.notesplus.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY modifiedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE pdfRemoteId = :remoteId LIMIT 1")
    suspend fun byPdfRemoteId(remoteId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE inkRemoteId = :remoteId LIMIT 1")
    suspend fun byInkRemoteId(remoteId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE relativeDir = :dir AND fileName = :fileName LIMIT 1")
    suspend fun byPath(dir: String, fileName: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE pdfDirty = 1 OR inkDirty = 1")
    suspend fun dirty(): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE pdfDirty = 1 OR inkDirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE notes SET inkDirty = 1, modifiedAt = :now, pageCount = :pageCount WHERE id = :id")
    suspend fun markInkDirty(id: String, now: Long, pageCount: Int)

    @Query("UPDATE notes SET syncError = :error WHERE id = :id")
    suspend fun setSyncError(id: String, error: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun queueDelete(item: PendingDeleteEntity)

    @Query("SELECT * FROM pending_deletes")
    suspend fun pendingDeletes(): List<PendingDeleteEntity>

    @Delete
    suspend fun clearPendingDelete(item: PendingDeleteEntity)
}
