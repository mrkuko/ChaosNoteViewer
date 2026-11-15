package com.chaosnoteviewer.sync

import java.time.Instant

// An in-memory Firestore for the tests: documents with server times (strictly increasing, microsecond
// resolution), the pull order (time, id) with a cursor, the key check, and switches to break things.
// Several devices share one instance, like several phones share one cloud.
class FakeRemoteStore : RemoteStore {
    class Doc(var content: String, var ts: Instant)

    val notes = mutableMapOf<String, MutableMap<String, Doc>>()
    val checks = mutableMapOf<String, String>()
    var offline = false
    var failNextUpload: SyncException? = null
    var uploads = 0              // calls, to see that an idle sync sends nothing
    var listIdsCalls = 0
    var pulls = 0

    private var counter = 0L
    private val base = Instant.parse("2026-01-01T00:00:00Z")
    private fun tick(): Instant = base.plusNanos(++counter * 1000)
    private fun docs(uid: String) = notes.getOrPut(uid) { mutableMapOf() }
    private fun net() { if (offline) throw SyncException("NETWORK", "Could not reach Firestore.") }

    fun docIds(uid: String) = docs(uid).keys.toSet()
    fun content(uid: String, id: String) = docs(uid)[id]?.content

    // a document somebody else wrote, e.g. not encrypted
    fun put(uid: String, id: String, content: String) { docs(uid)[id] = Doc(content, tick()) }

    override suspend fun upload(uid: String, items: List<Pair<String, String>>): List<Long> {
        net()
        failNextUpload?.let { failNextUpload = null; throw it }
        uploads++
        return items.map { (id, content) ->
            val t = tick()
            docs(uid)[id] = Doc(content, t)
            t.toEpochMilli()
        }
    }

    override suspend fun delete(uid: String, ids: List<String>) {
        net()
        ids.forEach { docs(uid).remove(it) }
    }

    override suspend fun pull(uid: String, after: Pair<String, String>?, limit: Int): List<RemoteDoc> {
        net()
        pulls++
        val cursor = after?.let { Instant.parse(it.first) to it.second }
        return docs(uid).entries
            .map { (id, d) -> Triple(d.ts, id, d) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .filter { (ts, id, _) -> cursor == null || ts > cursor.first || (ts == cursor.first && id > cursor.second) }
            .take(limit)
            .map { (ts, id, d) -> RemoteDoc(id, ts.toString(), ts.toEpochMilli(), d.content) }
    }

    override suspend fun count(uid: String): Int { net(); return docs(uid).size }

    override suspend fun listIds(uid: String): List<String> { net(); listIdsCalls++; return docs(uid).keys.sorted() }

    override suspend fun fetchKeyCheck(uid: String): String? { net(); return checks[uid] }

    override suspend fun createKeyCheck(uid: String, text: String): Boolean {
        net()
        if (uid in checks) return false
        checks[uid] = text
        return true
    }

    override suspend fun wipe(uid: String) {
        net()
        docs(uid).clear()
        checks.remove(uid)
    }
}
