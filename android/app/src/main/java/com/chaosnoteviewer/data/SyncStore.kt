package com.chaosnoteviewer.data

// A note as it is stored in the cloud (the encrypted JSON), see sync/NoteJson.kt.
data class RemoteNote(
    val title: String,
    val description: String,
    val completed: Boolean,
    // missing in a note written by a client that doesn't know stars: not starred
    val starred: Boolean,
    val position: Double,
    val dueDate: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

// A pulled document. [note] is null when it can't be read (not ours, other format): skipped, the cursor still moves.
// [tsRaw] is the server time exactly as Firestore sent it (RFC 3339 with microseconds): rounding it to ms
// would fetch the same document forever. [tsMs] is the same time in ms.
data class RemoteItem(val id: String, val tsRaw: String, val tsMs: Long, val note: RemoteNote?)

// What the key flow changes in the notes.
interface KeyFlowStore {
    // the key was created here: anything uploaded before without a key goes up again, encrypted
    suspend fun markAllDirty()

    // the cloud copy was wiped (key reset): every note goes up again, pending deletes are moot
    suspend fun forgetRemote()
}

// What the sync needs from the local database (NotesRepository implements it).
interface SyncStore : StateStore, KeyFlowStore {
    suspend fun dirtyRows(): List<NoteEntity>
    suspend fun markPushed(id: String, updatedAt: Long, remoteMs: Long)
    suspend fun purge(id: String, updatedAt: Long)

    // Merges pulled documents (last write wins on updated_at) and moves the pull cursor past them, in one
    // transaction. Returns how many notes changed on this device.
    suspend fun applyRemote(items: List<RemoteItem>, uid: String): Int

    suspend fun syncedIds(): List<String>
    suspend fun reupload(id: String)
}
