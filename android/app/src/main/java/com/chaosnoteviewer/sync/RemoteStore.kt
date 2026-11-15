package com.chaosnoteviewer.sync

// code: "UNAUTHENTICATED" (the id token expired), "NETWORK", "BAD_KEY" (a document can't be decrypted with this
// key) or Firestore's status ("PERMISSION_DENIED", ...), as on desktop.
class SyncException(val code: String, message: String) : Exception(message)

// A document of users/{uid}/notes, see core/firebase/firestore.rules. [content] is "v1.<encrypted note>".
// Times: [tsRaw] as the server sent it (microseconds), [tsMs] in ms.
data class RemoteDoc(val id: String, val tsRaw: String, val tsMs: Long, val content: String?)

// The cloud copy of one account (Firestore). Every call throws SyncException.
interface RemoteStore {
    // Writes the documents in one commit with the server time as serverUpdatedAt (the rules demand it).
    // Returns the write time of each, in ms. [items] = (note id, content).
    suspend fun upload(uid: String, items: List<Pair<String, String>>): List<Long>

    // Deletes for real. Deleting what is already gone counts as success.
    suspend fun delete(uid: String, ids: List<String>)

    // The next [limit] documents after the cursor (tsRaw, id), oldest first; from the start when [after] is null.
    suspend fun pull(uid: String, after: Pair<String, String>?, limit: Int): List<RemoteDoc>

    suspend fun count(uid: String): Int

    // all note ids (names only, no content)
    suspend fun listIds(uid: String): List<String>

    // the key check text, null = there is none yet
    suspend fun fetchKeyCheck(uid: String): String?

    // Creates the key check, but only when there is none, so two devices can't each make their own key.
    // false = another device was first.
    suspend fun createKeyCheck(uid: String, text: String): Boolean

    // deletes every note and the key check (key reset)
    suspend fun wipe(uid: String)
}
