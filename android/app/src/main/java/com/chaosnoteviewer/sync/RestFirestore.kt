package com.chaosnoteviewer.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

// Firestore over its REST API, the same calls as desktop Sync.js (there is no reason to use the SDK here: this
// keeps the exact server time of every document, fails fast when offline and has no hidden queue of writes).
//
//   users/{uid}/notes/{noteId}   content: "v1.<encrypted JSON of the note>", serverUpdatedAt
//   users/{uid}/meta/keycheck    check: a known text encrypted with the key
//
// [baseUrl]: https://firestore.googleapis.com/v1, or http://localhost:43080/v1 for the local emulator. Both it and
// [projectId] are read at every call: the user can enter or change their Firebase project while the app runs.
// [token] gives the Firebase id token; with forceRefresh = true a new one (the old one expired).
class RestFirestore(
    private val baseUrl: () -> String,
    private val projectId: () -> String,
    private val token: suspend (forceRefresh: Boolean) -> String,
) : RemoteStore {
    constructor(baseUrl: String, projectId: String, token: suspend (forceRefresh: Boolean) -> String) :
        this({ baseUrl }, { projectId }, token)

    companion object {
        const val LIST_PAGE = 1000
        const val DELETE_BATCH = 400
        const val PUSH_BATCH = 100
        private const val TIMEOUT_MS = 20_000
    }

    private val db get() = "projects/${projectId()}/databases/(default)/documents"
    private fun root(uid: String) = "$db/users/$uid"
    private fun noteName(uid: String, id: String) = "${root(uid)}/notes/$id"
    private fun checkName(uid: String) = "${root(uid)}/meta/keycheck"

    // ---- HTTP ----

    // JSONObject or JSONArray. An expired token is refreshed once and the call repeated.
    private suspend fun call(method: String, path: String, body: JSONObject?): Any {
        return try {
            send(method, path, body, token(false))
        } catch (e: SyncException) {
            if (e.code != "UNAUTHENTICATED") throw e
            send(method, path, body, token(true))
        }
    }

    private suspend fun send(method: String, path: String, body: JSONObject?, bearer: String): Any =
        withContext(Dispatchers.IO) {
            val conn = URL("${baseUrl()}/$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val data = try { JSONTokener(text).nextValue() } catch (e: JSONException) { null }
                when {
                    status in 200..299 && (data is JSONObject || data is JSONArray) -> data
                    data == null || data !is JSONObject && data !is JSONArray ->
                        throw SyncException("NETWORK", "Could not reach Firestore.")
                    else -> {
                        val err = (if (data is JSONArray) data.optJSONObject(0) else data as JSONObject)?.optJSONObject("error")
                        throw SyncException(
                            err?.optString("status")?.takeIf { it.isNotEmpty() } ?: "HTTP_$status",
                            err?.optString("message")?.takeIf { it.isNotEmpty() } ?: "Sync failed.",
                        )
                    }
                }
            } catch (e: IOException) {
                throw SyncException("NETWORK", "Could not reach Firestore.")
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun commit(writes: JSONArray): JSONObject =
        call("POST", "$db:commit", JSONObject().put("writes", writes)) as JSONObject

    private fun ms(ts: String) = Instant.parse(ts).toEpochMilli()

    // ---- notes ----

    override suspend fun upload(uid: String, items: List<Pair<String, String>>): List<Long> {
        val times = ArrayList<Long>(items.size)
        for (batch in items.chunked(PUSH_BATCH)) {
            val writes = JSONArray()
            for ((id, content) in batch)
                writes.put(
                    JSONObject()
                        .put(
                            "update",
                            JSONObject().put("name", noteName(uid, id))
                                .put("fields", JSONObject().put("content", JSONObject().put("stringValue", content))),
                        )
                        .put(
                            "updateTransforms",
                            JSONArray().put(JSONObject().put("fieldPath", "serverUpdatedAt").put("setToServerValue", "REQUEST_TIME")),
                        ),
                )
            val results = commit(writes).getJSONArray("writeResults")
            for (i in batch.indices)
                times += ms(results.getJSONObject(i).getString("updateTime"))
        }
        return times
    }

    override suspend fun delete(uid: String, ids: List<String>) {
        for (batch in ids.chunked(DELETE_BATCH))
            commit(JSONArray().apply { batch.forEach { put(JSONObject().put("delete", noteName(uid, it))) } })
    }

    private suspend fun runQuery(uid: String, query: JSONObject): JSONArray =
        call("POST", "${root(uid)}:runQuery", JSONObject().put("structuredQuery", query)) as JSONArray

    override suspend fun pull(uid: String, after: Pair<String, String>?, limit: Int): List<RemoteDoc> {
        val query = JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "notes")))
            .put(
                "orderBy",
                JSONArray()
                    .put(JSONObject().put("field", JSONObject().put("fieldPath", "serverUpdatedAt")).put("direction", "ASCENDING"))
                    .put(JSONObject().put("field", JSONObject().put("fieldPath", "__name__")).put("direction", "ASCENDING")),
            )
            .put("limit", limit)
        if (after != null)
            query.put(
                "startAt",
                JSONObject().put("before", false).put(
                    "values",
                    JSONArray()
                        .put(JSONObject().put("timestampValue", after.first))
                        .put(JSONObject().put("referenceValue", noteName(uid, after.second))),
                ),
            )
        val rows = runQuery(uid, query)
        val docs = ArrayList<RemoteDoc>()
        for (i in 0 until rows.length()) {
            val doc = rows.getJSONObject(i).optJSONObject("document") ?: continue
            val fields = doc.optJSONObject("fields") ?: continue
            val tsRaw = fields.optJSONObject("serverUpdatedAt")?.optString("timestampValue")?.takeIf { it.isNotEmpty() } ?: continue
            docs += RemoteDoc(
                id = doc.getString("name").substringAfterLast('/'),
                tsRaw = tsRaw,
                tsMs = ms(tsRaw),
                content = fields.optJSONObject("content")?.optString("stringValue"),
            )
        }
        return docs
    }

    override suspend fun count(uid: String): Int {
        val body = JSONObject().put(
            "structuredAggregationQuery",
            JSONObject()
                .put("structuredQuery", JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "notes"))))
                .put("aggregations", JSONArray().put(JSONObject().put("alias", "n").put("count", JSONObject()))),
        )
        val d = call("POST", "${root(uid)}:runAggregationQuery", body) as JSONArray
        return d.getJSONObject(0).getJSONObject("result").getJSONObject("aggregateFields")
            .getJSONObject("n").getString("integerValue").toInt()
    }

    override suspend fun listIds(uid: String): List<String> {
        val ids = ArrayList<String>()
        var after: String? = null
        while (true) {
            val query = JSONObject()
                .put("from", JSONArray().put(JSONObject().put("collectionId", "notes")))
                .put("select", JSONObject().put("fields", JSONArray().put(JSONObject().put("fieldPath", "__name__"))))
                .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "__name__")).put("direction", "ASCENDING")))
                .put("limit", LIST_PAGE)
            if (after != null)
                query.put(
                    "startAt",
                    JSONObject().put("before", false)
                        .put("values", JSONArray().put(JSONObject().put("referenceValue", noteName(uid, after)))),
                )
            val rows = runQuery(uid, query)
            var got = 0
            for (i in 0 until rows.length()) {
                val doc = rows.getJSONObject(i).optJSONObject("document") ?: continue
                ids += doc.getString("name").substringAfterLast('/')
                got++
            }
            if (got < LIST_PAGE) return ids
            after = ids.last()
        }
    }

    // ---- the key check document ----

    override suspend fun fetchKeyCheck(uid: String): String? = try {
        val d = call("GET", checkName(uid), null) as JSONObject
        d.optJSONObject("fields")?.optJSONObject("check")?.optString("stringValue").orEmpty()
    } catch (e: SyncException) {
        if (e.code == "NOT_FOUND") null else throw e
    }

    override suspend fun createKeyCheck(uid: String, text: String): Boolean = try {
        commit(
            JSONArray().put(
                JSONObject()
                    .put(
                        "update",
                        JSONObject().put("name", checkName(uid))
                            .put("fields", JSONObject().put("check", JSONObject().put("stringValue", text))),
                    )
                    .put("currentDocument", JSONObject().put("exists", false)),
            ),
        )
        true
    } catch (e: SyncException) {
        if (e.code == "ALREADY_EXISTS" || e.code == "FAILED_PRECONDITION") false else throw e
    }

    override suspend fun wipe(uid: String) {
        val names = listIds(uid).map { noteName(uid, it) } + checkName(uid)
        for (batch in names.chunked(DELETE_BATCH))
            commit(JSONArray().apply { batch.forEach { put(JSONObject().put("delete", it)) } })
    }
}
