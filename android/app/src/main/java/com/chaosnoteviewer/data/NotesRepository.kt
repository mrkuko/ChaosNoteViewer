package com.chaosnoteviewer.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// The only way the app changes notes. Every change bumps updated_at and sets dirty (queued for sync);
// a delete hides the note (deleted_at) until the sync removed it on the server.
// [now] is replaceable so tests control time.
class NotesRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : com.chaosnoteviewer.account.AccountStore, SyncStore {
    private val notes = db.notes()
    private val state = db.syncState()

    // live notes in queue order
    val alive: Flow<List<NoteEntity>> = notes.observeAlive()

    fun observe(id: String): Flow<NoteEntity?> = notes.observe(id)

    suspend fun get(id: String): NoteEntity? = notes.get(id)

    // New note at the end of the queue. dueDate: local midnight of the deadline day, null = none.
    suspend fun add(title: String, description: String = "", dueDate: Long? = null): NoteEntity =
        db.withTransaction {
            val t = now()
            val note = NoteEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                description = description,
                position = notes.nextPosition(),
                dueDate = dueDate,
                createdAt = t,
                updatedAt = t,
                dirty = true,
            )
            notes.insert(note)
            note
        }

    suspend fun update(id: String, title: String, description: String, dueDate: Long?) =
        notes.updateText(id, title, description, dueDate, now())

    suspend fun setCompleted(id: String, completed: Boolean) =
        notes.setCompleted(id, completed, now())

    // A note that was never on the server is removed, a synced one is hidden and queued for the sync.
    suspend fun delete(id: String) = db.withTransaction {
        notes.deleteUnsynced(id)
        notes.markDeleted(id, now())
    }

    suspend fun deleteCompleted() = db.withTransaction {
        notes.completedIds().forEach { delete(it) }
    }

    // Moves the note shown at row [from] of [visible] to row [to]; renumbers first when the neighbours
    // are too close together.
    suspend fun move(visible: List<NoteEntity>, from: Int, to: Int) = db.withTransaction {
        when (val drop = Positions.drop(visible.map { it.position }, from, to)) {
            Positions.Drop.Unchanged -> Unit
            is Positions.Drop.At -> notes.setPosition(visible[from].id, drop.position, now())
            Positions.Drop.NeedsRenumber -> {
                renumber()
                val fresh = visible.map { v -> notes.get(v.id)!!.position }
                val again = Positions.drop(fresh, from, to)
                if (again is Positions.Drop.At)
                    notes.setPosition(visible[from].id, again.position, now())
            }
        }
    }

    // Renumbers live notes 1, 2, 3, ... keeping their order.
    suspend fun renumber() = db.withTransaction {
        val t = now()
        notes.alive().forEachIndexed { i, n -> notes.setPosition(n.id, (i + 1).toDouble(), t) }
    }

    // ---- sync ----

    override suspend fun dirtyRows(): List<NoteEntity> = notes.dirtyRows()
    override suspend fun markPushed(id: String, updatedAt: Long, remoteMs: Long) = notes.markPushed(id, updatedAt, remoteMs)
    override suspend fun purge(id: String, updatedAt: Long) = notes.purge(id, updatedAt)
    override suspend fun syncedIds(): List<String> = notes.syncedIds()
    override suspend fun reupload(id: String) = notes.reupload(id)

    override suspend fun applyRemote(items: List<RemoteItem>, uid: String): Int = db.withTransaction {
        var changed = 0
        for (item in items) {
            val n = item.note ?: continue
            val local = notes.get(item.id)
            when {
                local == null -> {
                    notes.insert(
                        NoteEntity(
                            id = item.id, authorId = uid, title = n.title, description = n.description,
                            completed = n.completed, position = n.position, dueDate = n.dueDate,
                            createdAt = n.createdAt, updatedAt = n.updatedAt, deletedAt = null,
                            dirty = false, remoteUpdatedAt = item.tsMs,
                        ),
                    )
                    changed++
                }
                // the local version is newer or the same (e.g. our own push coming back)
                local.updatedAt >= n.updatedAt -> if (!local.dirty) notes.setRemoteUpdatedAt(item.id, item.tsMs)
                else -> {
                    notes.overwriteFromRemote(
                        item.id, n.title, n.description, n.completed, n.position, n.dueDate, n.updatedAt, item.tsMs,
                    )
                    changed++
                }
            }
        }
        items.lastOrNull()?.let {
            state.set(SyncStateEntity(StateKeys.CURSOR_TS, it.tsRaw))
            state.set(SyncStateEntity(StateKeys.CURSOR_ID, it.id))
        }
        changed
    }

    override suspend fun forgetRemote() = db.withTransaction {
        notes.deleteAllDeleted()
        notes.markAllForUpload()
        state.clear(StateKeys.CURSOR_TS)
        state.clear(StateKeys.CURSOR_ID)
    }

    override suspend fun markAllDirty() = notes.markAllDirty()

    override suspend fun claimNotes(uid: String) = notes.claim(uid, now())
    override suspend fun rebindNotes(oldUid: String, newUid: String) = notes.rebind(oldUid, newUid, now())

    override suspend fun getState(key: String): String = state.get(key) ?: ""
    override suspend fun setState(key: String, value: String) = state.set(SyncStateEntity(key, value))
    suspend fun clearState(key: String) = state.clear(key)
}
