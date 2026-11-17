package com.chaosnoteviewer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// One row of `notes`, exactly as core/storage/migrations/001_notes.sql, 002_sync.sql and 003_starred.sql define it
// (NoteSchemaTest builds a database from those files and opens it with Room). Room can't express the
// CHECK constraints and the partial index of the SQL files; the data written here satisfies them anyway.
// Times are ms since the Unix epoch (UTC).
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["deleted_at", "position"], name = "notes_queue"),
        Index(value = ["dirty"], name = "notes_dirty"),
    ],
)
data class NoteEntity(
    // UUIDv4, generated on the device
    @PrimaryKey val id: String,
    // Firebase uid of the creator; null = created before signing in, claimed on the first sign-in
    @ColumnInfo(name = "author_id") val authorId: String? = null,
    val title: String,
    @ColumnInfo(defaultValue = "''") val description: String = "",
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
    // marked as important with a star; part of the synced note content (003_starred.sql)
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    // queue order, lower = higher priority; a double so a note fits between two neighbours
    val position: Double,
    // local midnight of the deadline day, null = no deadline
    @ColumnInfo(name = "due_date") val dueDate: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    // bumped on every change, last-write-wins tiebreaker for sync
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // null = alive; set on delete, the row is removed once the server confirmed the delete
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    // changed locally and not pushed yet
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    // Firestore serverUpdatedAt of the version last pulled/pushed, null = never synced
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
)
