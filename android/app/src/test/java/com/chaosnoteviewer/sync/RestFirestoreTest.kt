package com.chaosnoteviewer.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

// The REST calls against a small local HTTP server: what is sent (the same requests as desktop Sync.js) and how
// answers and errors are read.
class RestFirestoreTest {
    private class Seen(val method: String, val path: String, val auth: String?, val body: JSONObject?)

    private lateinit var server: HttpServer
    private val seen = mutableListOf<Seen>()
    private var reply: (Seen) -> Pair<Int, String> = { 200 to "{}" }
    private val tokens = mutableListOf<Boolean>()
    private lateinit var firestore: RestFirestore

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val text = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val s = Seen(ex.requestMethod, ex.requestURI.rawPath, ex.requestHeaders.getFirst("Authorization"),
                if (text.isEmpty()) null else JSONObject(text))
            seen += s
            val (code, body) = reply(s)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        firestore = newClient()
    }

    private fun newClient(url: String = "http://127.0.0.1:${server.address.port}/v1") =
        RestFirestore(url, "proj", { refresh -> tokens += refresh; if (refresh) "fresh" else "old" })

    @After fun stop() = server.stop(0)

    private val docs = "projects/proj/databases/(default)/documents"

    @Test fun uploadSendsTheContentAndAskForTheServerTime() = runTest {
        reply = { 200 to """{"writeResults":[{"updateTime":"2026-03-01T10:00:00.123456Z"},{"updateTime":"2026-03-01T10:00:01Z"}]}""" }
        val times = firestore.upload("u1", listOf("n1" to "v1.aaa", "n2" to "v1.bbb"))
        assertEquals(listOf(1772359200123L, 1772359201000L), times)
        val s = seen.single()
        assertEquals("POST", s.method)
        assertTrue(s.path.endsWith(":commit"))
        assertEquals("Bearer old", s.auth)
        val w = s.body!!.getJSONArray("writes")
        assertEquals(2, w.length())
        assertEquals("$docs/users/u1/notes/n1", w.getJSONObject(0).getJSONObject("update").getString("name"))
        assertEquals("v1.aaa", w.getJSONObject(0).getJSONObject("update").getJSONObject("fields").getJSONObject("content").getString("stringValue"))
        val tr = w.getJSONObject(1).getJSONArray("updateTransforms").getJSONObject(0)
        assertEquals("serverUpdatedAt", tr.getString("fieldPath"))
        assertEquals("REQUEST_TIME", tr.getString("setToServerValue"))      // the rules demand the server time
    }

    @Test fun uploadsAreSplitIntoBatchesOf100() = runTest {
        reply = { r ->
            val n = r.body!!.getJSONArray("writes").length()
            200 to JSONObject().put("writeResults", JSONArray().apply { repeat(n) { put(JSONObject().put("updateTime", "2026-03-01T10:00:00Z")) } }).toString()
        }
        val times = firestore.upload("u1", (1..250).map { "n$it" to "v1.x" })
        assertEquals(250, times.size)
        assertEquals(listOf(100, 100, 50), seen.map { it.body!!.getJSONArray("writes").length() })
    }

    @Test fun deleteSendsDeletes() = runTest {
        firestore.delete("u1", listOf("a", "b"))
        val w = seen.single().body!!.getJSONArray("writes")
        assertEquals("$docs/users/u1/notes/a", w.getJSONObject(0).getString("delete"))
        assertEquals("$docs/users/u1/notes/b", w.getJSONObject(1).getString("delete"))
    }

    @Test fun pullFromTheStartHasNoCursorAndTheDocumentOrder() = runTest {
        reply = { 200 to """[{"readTime":"2026-03-01T10:00:00Z"}]""" }          // an empty result is one row without a document
        assertEquals(emptyList<RemoteDoc>(), firestore.pull("u1", null, 200))
        val q = seen.single().body!!.getJSONObject("structuredQuery")
        assertEquals(200, q.getInt("limit"))
        assertFalse(q.has("startAt"))
        assertEquals("serverUpdatedAt", q.getJSONArray("orderBy").getJSONObject(0).getJSONObject("field").getString("fieldPath"))
        assertEquals("__name__", q.getJSONArray("orderBy").getJSONObject(1).getJSONObject("field").getString("fieldPath"))
        assertTrue(seen.single().path.endsWith(":runQuery"))
    }

    @Test fun pullAfterTheCursorStartsAfterItWithMicrosecondsKept() = runTest {
        reply = { 200 to """[{"document":{"name":"$docs/users/u1/notes/n7","fields":{"content":{"stringValue":"v1.zzz"},"serverUpdatedAt":{"timestampValue":"2026-03-01T10:00:00.123456Z"}}}},{"readTime":"x"}]""" }
        val docs = firestore.pull("u1", "2026-03-01T09:59:59.999999Z" to "n3", 200)
        val start = seen.single().body!!.getJSONObject("structuredQuery").getJSONObject("startAt")
        assertFalse(start.getBoolean("before"))                                   // strictly after the cursor document
        assertEquals("2026-03-01T09:59:59.999999Z", start.getJSONArray("values").getJSONObject(0).getString("timestampValue"))
        assertEquals("${this@RestFirestoreTest.docs}/users/u1/notes/n3", start.getJSONArray("values").getJSONObject(1).getString("referenceValue"))
        val d = docs.single()
        assertEquals("n7", d.id)
        assertEquals("v1.zzz", d.content)
        assertEquals("2026-03-01T10:00:00.123456Z", d.tsRaw)                      // exactly as the server sent it
        assertEquals(1772359200123L, d.tsMs)
    }

    @Test fun documentsWithoutAServerTimeAreIgnored() = runTest {
        reply = { 200 to """[{"document":{"name":"$docs/users/u1/notes/n1","fields":{"content":{"stringValue":"x"}}}}]""" }
        assertEquals(emptyList<RemoteDoc>(), firestore.pull("u1", null, 10))
    }

    @Test fun countReadsTheAggregate() = runTest {
        reply = { 200 to """[{"result":{"aggregateFields":{"n":{"integerValue":"42"}}}}]""" }
        assertEquals(42, firestore.count("u1"))
        assertTrue(seen.single().path.endsWith(":runAggregationQuery"))
    }

    @Test fun listIdsAsksForNamesOnly() = runTest {
        reply = { 200 to """[{"document":{"name":"$docs/users/u1/notes/b"}},{"document":{"name":"$docs/users/u1/notes/a"}}]""" }
        assertEquals(listOf("b", "a"), firestore.listIds("u1"))
        val sel = seen.single().body!!.getJSONObject("structuredQuery").getJSONObject("select").getJSONArray("fields")
        assertEquals("__name__", sel.getJSONObject(0).getString("fieldPath"))
    }

    @Test fun keyCheckNotFoundIsNull() = runTest {
        reply = { 404 to """{"error":{"code":404,"message":"Document not found","status":"NOT_FOUND"}}""" }
        assertNull(firestore.fetchKeyCheck("u1"))
    }

    @Test fun keyCheckIsRead() = runTest {
        reply = { 200 to """{"name":"x","fields":{"check":{"stringValue":"v1.abc"}}}""" }
        assertEquals("v1.abc", firestore.fetchKeyCheck("u1"))
        assertTrue(seen.single().path.endsWith("/users/u1/meta/keycheck"))
    }

    @Test fun creatingTheKeyCheckOnlyWhenThereIsNone() = runTest {
        assertTrue(firestore.createKeyCheck("u1", "v1.abc"))
        val w = seen.single().body!!.getJSONArray("writes").getJSONObject(0)
        assertFalse(w.getJSONObject("currentDocument").getBoolean("exists"))
        reply = { 409 to """{"error":{"code":409,"message":"exists","status":"ALREADY_EXISTS"}}""" }
        assertFalse(firestore.createKeyCheck("u1", "v1.def"))
        reply = { 400 to """{"error":{"code":400,"message":"precondition","status":"FAILED_PRECONDITION"}}""" }
        assertFalse(firestore.createKeyCheck("u1", "v1.def"))
    }

    @Test fun aRefusedRequestKeepsFirestoresStatus() = runTest {
        reply = { 403 to """{"error":{"code":403,"message":"Missing or insufficient permissions.","status":"PERMISSION_DENIED"}}""" }
        try { firestore.delete("u1", listOf("a")); fail() } catch (e: SyncException) {
            assertEquals("PERMISSION_DENIED", e.code)
            assertEquals("Missing or insufficient permissions.", e.message)
        }
        reply = { 400 to """[{"error":{"code":400,"message":"bad","status":"INVALID_ARGUMENT"}}]""" }     // errors can come in an array
        try { firestore.delete("u1", listOf("a")); fail() } catch (e: SyncException) { assertEquals("INVALID_ARGUMENT", e.code) }
        reply = { 500 to """{"oops":1}""" }
        try { firestore.delete("u1", listOf("a")); fail() } catch (e: SyncException) { assertEquals("HTTP_500", e.code) }
    }

    @Test fun anExpiredTokenIsRefreshedOnceAndTheCallRepeated() = runTest {
        reply = { r ->
            if (r.auth == "Bearer old") 401 to """{"error":{"code":401,"message":"expired","status":"UNAUTHENTICATED"}}"""
            else 200 to """{"writeResults":[]}"""
        }
        firestore.delete("u1", listOf("a"))
        assertEquals(listOf(false, true), tokens)
        assertEquals(listOf("Bearer old", "Bearer fresh"), seen.map { it.auth })
    }

    @Test fun aSecondRefusalAfterTheRefreshIsAnError() = runTest {
        reply = { 401 to """{"error":{"code":401,"message":"no","status":"UNAUTHENTICATED"}}""" }
        try { firestore.delete("u1", listOf("a")); fail() } catch (e: SyncException) { assertEquals("UNAUTHENTICATED", e.code) }
        assertEquals(2, seen.size)                       // not an endless loop
    }

    @Test fun noConnectionIsNetwork() = runTest {
        val dead = newClient("http://127.0.0.1:1/v1")
        try { dead.count("u1"); fail() } catch (e: SyncException) { assertEquals("NETWORK", e.code) }
    }

    @Test fun anAnswerThatIsNotJsonIsNetwork() = runTest {
        reply = { 200 to "<html>captive portal</html>" }
        try { firestore.count("u1"); fail() } catch (e: SyncException) { assertEquals("NETWORK", e.code) }
    }

    @Test fun wipeDeletesEveryNoteAndTheCheck() = runTest {
        reply = { r ->
            if (r.path.endsWith(":runQuery")) 200 to """[{"document":{"name":"$docs/users/u1/notes/a"}},{"document":{"name":"$docs/users/u1/notes/b"}}]"""
            else 200 to """{"writeResults":[]}"""
        }
        firestore.wipe("u1")
        val deletes = seen.last().body!!.getJSONArray("writes")
        assertEquals(3, deletes.length())
        assertTrue(deletes.getJSONObject(2).getString("delete").endsWith("/users/u1/meta/keycheck"))
    }
}
