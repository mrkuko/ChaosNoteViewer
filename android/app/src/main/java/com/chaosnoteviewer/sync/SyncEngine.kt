package com.chaosnoteviewer.sync

import com.chaosnoteviewer.crypto.NoteCrypto
import com.chaosnoteviewer.data.RemoteItem
import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.SyncStore

data class SyncResult(val pushed: Int, val pulled: Int)

// Push what changed here, then pull what changed elsewhere. Same algorithm as desktop Sync.js.
//
// Deleting is real: the delete is sent first and the local row is removed only after the server confirmed it.
// Notes deleted elsewhere stay on the devices that still have them; reconcile() finds those so the UI can mark
// them (the user decides: upload again, or delete here).
//
// Cost: a push is one request per 100 changes and nothing when nothing changed. A pull is one query returning
// only documents newer than the cursor, so an idle poll reads no documents.
class SyncEngine(private val store: SyncStore, private val remote: RemoteStore) {
    companion object {
        const val PUSH_BATCH = 100
        const val PULL_PAGE = 200
    }

    // done(pushed, pulled); throws SyncException ("BAD_KEY" when the key doesn't fit the cloud copy)
    suspend fun run(uid: String, key: ByteArray): SyncResult {
        val pushed = push(uid, key)
        val pulled = pull(uid, key)
        return SyncResult(pushed, pulled)
    }

    suspend fun push(uid: String, key: ByteArray): Int {
        val uploads = mutableListOf<com.chaosnoteviewer.data.NoteEntity>()
        val removals = mutableListOf<com.chaosnoteviewer.data.NoteEntity>()
        for (row in store.dirtyRows()) {
            when {
                row.deletedAt == null -> uploads += row
                row.remoteUpdatedAt == null -> store.purge(row.id, row.updatedAt)     // never uploaded: nothing to delete
                else -> removals += row
            }
        }
        var sent = 0
        // uploads first, and a commit never mixes the two: one refused write rejects the whole commit, and a
        // refused delete must not keep the new and changed notes from reaching the server
        for (batch in uploads.chunked(PUSH_BATCH)) {
            val times = remote.upload(uid, batch.map { it.id to NoteCrypto.encrypt(key, NoteJson.encode(it), it.id) })
            batch.forEachIndexed { i, row -> store.markPushed(row.id, row.updatedAt, times[i]) }
            sent += batch.size
        }
        for (batch in removals.chunked(PUSH_BATCH)) {
            remote.delete(uid, batch.map { it.id })
            batch.forEach { store.purge(it.id, it.updatedAt) }
            sent += batch.size
        }
        return sent
    }

    suspend fun pull(uid: String, key: ByteArray): Int {
        var changed = 0
        while (true) {
            val ts = store.getState(StateKeys.CURSOR_TS)
            val after = if (ts.isEmpty()) null else ts to store.getState(StateKeys.CURSOR_ID)
            val docs = remote.pull(uid, after, PULL_PAGE)
            val items = docs.map { doc ->
                var note: com.chaosnoteviewer.data.RemoteNote? = null
                val content = doc.content
                if (content != null && content.startsWith(NoteCrypto.VERSION)) {
                    // encrypted for us but not readable: a different key. Stop before changing anything.
                    val json = NoteCrypto.decrypt(key, content, doc.id)
                        ?: throw SyncException("BAD_KEY", "The encryption key does not fit the notes in the cloud.")
                    note = NoteJson.parse(json)
                }
                // anything else (not encrypted, other format) is skipped, the cursor still moves past it
                RemoteItem(doc.id, doc.tsRaw, doc.tsMs, note)
            }
            changed += store.applyRemote(items, uid)
            if (items.size < PULL_PAGE)
                return changed
        }
    }

    // Ids of notes this device has synced before that are not on the server any more. After run() every note
    // on the server is also here, so the counts match unless something vanished (or is unreadable); only then
    // are the ids fetched.
    suspend fun reconcile(uid: String): List<String> {
        val local = store.syncedIds()
        if (remote.count(uid) == local.size)
            return emptyList()
        val there = remote.listIds(uid).toHashSet()
        return local.filter { it !in there }
    }
}
