package com.chaosnoteviewer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Raw table access. Only NotesRepository uses it: the rules (updated_at, dirty, soft delete) live there.
@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY position ASC, id ASC")
    fun observeAlive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY position ASC, id ASC")
    suspend fun alive(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM notes WHERE deleted_at IS NULL")
    suspend fun nextPosition(): Double

    @Insert
    suspend fun insert(note: NoteEntity)

    @Query("UPDATE notes SET title = :title, description = :description, due_date = :dueDate, updated_at = :now, dirty = 1 WHERE id = :id")
    suspend fun updateText(id: String, title: String, description: String, dueDate: Long?, now: Long)

    @Query("UPDATE notes SET completed = :completed, updated_at = :now, dirty = 1 WHERE id = :id")
    suspend fun setCompleted(id: String, completed: Boolean, now: Long)

    @Query("UPDATE notes SET position = :position, updated_at = :now, dirty = 1 WHERE id = :id")
    suspend fun setPosition(id: String, position: Double, now: Long)

    // never on the server: nothing to delete there, the row just goes
    @Query("DELETE FROM notes WHERE id = :id AND remote_updated_at IS NULL")
    suspend fun deleteUnsynced(id: String)

    @Query("UPDATE notes SET deleted_at = :now, updated_at = :now, dirty = 1 WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)

    // notes written before any sign-in belong to the first account that signs in
    @Query("UPDATE notes SET author_id = :uid, updated_at = :now, dirty = 1 WHERE author_id IS NULL")
    suspend fun claim(uid: String, now: Long)

    @Query("UPDATE notes SET author_id = :newUid, updated_at = :now, dirty = 1 WHERE author_id = :oldUid")
    suspend fun rebind(oldUid: String, newUid: String, now: Long)

    @Query("SELECT id FROM notes WHERE deleted_at IS NULL AND completed = 1")
    suspend fun completedIds(): List<String>
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun set(entry: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun clear(key: String)
}
