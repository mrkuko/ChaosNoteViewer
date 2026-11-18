package com.chaosnoteviewer.account

import com.chaosnoteviewer.crypto.KeyWrapper
import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import java.net.URLDecoder
import java.util.Base64

// The sign-in calls against a small local server standing in for Identity Toolkit and Secure Token: what is sent
// (the same requests as desktop Auth.js), how the session is kept, and how errors are read.
class RestAuthGatewayTest {
    private class Seen(val path: String, val query: String, val body: String)

    private lateinit var server: HttpServer
    private val seen = mutableListOf<Seen>()
    private var reply: (Seen) -> Pair<Int, String> = { 200 to "{}" }

    private val state = mutableMapOf<String, String>()
    private val store = object : StateStore {
        override suspend fun getState(key: String) = state[key] ?: ""
        override suspend fun setState(key: String, value: String) { state[key] = value }
    }
    // stands in for the Android Keystore: reversible, but not the plain text
    private val wrapper = object : KeyWrapper {
        override fun wrap(key: ByteArray) = "w." + Base64.getEncoder().encodeToString(key)
        override fun unwrap(blob: String) = if (blob.startsWith("w.")) Base64.getDecoder().decode(blob.drop(2)) else null
    }
    private var clock = 1_000_000L
    private val key = "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"
    private var config: FirebaseConfig? = null
    private val writes = TestScope(UnconfinedTestDispatcher())

    private fun gateway() = RestAuthGateway({ config }, store, wrapper, writes, now = { clock })

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val s = Seen(ex.requestURI.rawPath, ex.requestURI.rawQuery.orEmpty(), ex.requestBody.readBytes().toString(Charsets.UTF_8))
            seen += s
            val (code, body) = reply(s)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        config = FirebaseConfig(key, "my-notes", server = "http://127.0.0.1:${server.address.port}")
    }

    @After fun stop() = server.stop(0)

    // an id token as Firebase makes them; only the claims part matters here
    private fun jwt(uid: String, email: String, verified: Boolean): String {
        val claims = JSONObject().put("user_id", uid).put("sub", uid).put("email", email).put("email_verified", verified)
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{}".toByteArray()) + "." + enc.encodeToString(claims.toString().toByteArray()) + ".sig"
    }

    private fun signedIn(uid: String = "u1", email: String = "a@b.c", verified: Boolean = true, refresh: String = "r1") =
        JSONObject().put("localId", uid).put("email", email).put("idToken", jwt(uid, email, verified))
            .put("refreshToken", refresh).put("expiresIn", "3600").toString()

    private fun refreshed(uid: String = "u1", email: String = "a@b.c", verified: Boolean = true, refresh: String = "r2") =
        JSONObject().put("user_id", uid).put("id_token", jwt(uid, email, verified))
            .put("refresh_token", refresh).put("expires_in", "3600").toString()

    private fun error(message: String, status: Int = 400) = status to JSONObject().put("error",
        JSONObject().put("code", status).put("message", message)).toString()

    @Test fun signInSendsTheSameRequestAsTheDesktop() = runTest {
        reply = { 200 to signedIn() }
        val user = gateway().signIn("a@b.c", "secret1")
        assertEquals(AuthUser("u1", "a@b.c", true), user)
        val s = seen.single()
        assertEquals("/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword", s.path)
        assertEquals("key=$key", s.query)
        val body = JSONObject(s.body)
        assertEquals("a@b.c", body.getString("email"))
        assertEquals("secret1", body.getString("password"))
        assertTrue(body.getBoolean("returnSecureToken"))
    }

    @Test fun signUpUsesItsEndpointAndAnUnverifiedUserIsPending() = runTest {
        reply = { 200 to signedIn(verified = false) }
        assertFalse(gateway().signUp("a@b.c", "secret1").verified)
        assertEquals("/identitytoolkit.googleapis.com/v1/accounts:signUp", seen.single().path)
    }

    // the refresh token survives a restart, wrapped; the user is known offline from what was saved
    @Test fun theSessionIsRememberedWrapped() = runTest {
        reply = { 200 to signedIn(refresh = "secret-refresh") }
        gateway().signIn("a@b.c", "secret1")
        val stored = state[StateKeys.REFRESH_TOKEN].orEmpty()
        assertTrue(stored.startsWith("w."))
        assertFalse(stored.contains("secret-refresh"))
        val restarted = gateway()
        assertEquals(AuthUser("u1", "a@b.c", true), restarted.remembered())
    }

    @Test fun nobodyRemembered() = runTest {
        assertNull(gateway().remembered())
        assertNull(gateway().reload())
    }

    // the id token is used while it is fresh; then, or when forced, it is exchanged with the refresh token
    @Test fun idTokenIsRefreshedWhenNeeded() = runTest {
        reply = { 200 to signedIn() }
        val g = gateway()
        g.signIn("a@b.c", "secret1")
        val first = g.idToken(false)
        assertEquals(1, seen.size)
        reply = { 200 to refreshed(refresh = "r2") }
        val forced = g.idToken(true)
        assertEquals(2, seen.size)
        val s = seen.last()
        assertEquals("/securetoken.googleapis.com/v1/token", s.path)
        assertEquals("key=$key", s.query)
        val form = s.body.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("r1", form["refresh_token"])
        assertTrue(first.isNotEmpty() && forced.isNotEmpty())
        // the new refresh token is the one kept from now on
        assertEquals("r2", String(wrapper.unwrap(state[StateKeys.REFRESH_TOKEN]!!)!!))
        clock += 3_600_000
        g.idToken(false)
        assertEquals(3, seen.size)
        assertEquals("r2", URLDecoder.decode(seen.last().body.substringAfter("refresh_token="), "UTF-8"))
    }

    // after a restart there is no id token in memory yet: the first call gets one
    @Test fun restartedSessionGetsATokenFirst() = runTest {
        reply = { 200 to signedIn() }
        gateway().signIn("a@b.c", "secret1")
        reply = { 200 to refreshed() }
        val restarted = gateway()
        restarted.idToken(false)
        assertEquals("/securetoken.googleapis.com/v1/token", seen.last().path)
    }

    // reload: a fresh token tells whether the email was verified in the meantime
    @Test fun reloadSeesTheEmailVerified() = runTest {
        reply = { 200 to signedIn(verified = false) }
        val g = gateway()
        g.signIn("a@b.c", "secret1")
        reply = { 200 to refreshed(verified = true) }
        assertTrue(g.reload()!!.verified)
        assertTrue(gateway().remembered()!!.verified)
    }

    @Test fun aRevokedSessionIsLostButOfflineIsNot() = runTest {
        reply = { 200 to signedIn() }
        val g = gateway()
        g.signIn("a@b.c", "secret1")
        reply = { error("TOKEN_EXPIRED") }
        try { g.idToken(true); fail() } catch (e: AuthException) { assertTrue(e.sessionLost) }
        reply = { error("USER_DISABLED") }
        try { g.reload(); fail() } catch (e: AuthException) { assertTrue(e.sessionLost) }
        server.stop(0)
        try { g.idToken(true); fail() } catch (e: AuthException) {
            assertEquals("NETWORK", e.code)
            assertFalse(e.sessionLost)
        }
    }

    @Test fun signOutForgetsTheSession() = runTest {
        reply = { 200 to signedIn() }
        val g = gateway()
        g.signIn("a@b.c", "secret1")
        g.signOut()
        assertNull(g.remembered())
        assertEquals("", state[StateKeys.REFRESH_TOKEN])
        assertNull(gateway().remembered())
        try { g.idToken(false); fail() } catch (e: AuthException) { assertTrue(e.sessionLost) }
    }

    @Test fun emails() = runTest {
        reply = { 200 to signedIn(verified = false) }
        val g = gateway()
        g.signIn("a@b.c", "secret1")
        reply = { 200 to "{}" }
        g.sendVerification()
        assertEquals("/identitytoolkit.googleapis.com/v1/accounts:sendOobCode", seen.last().path)
        assertEquals("VERIFY_EMAIL", JSONObject(seen.last().body).getString("requestType"))
        g.sendPasswordReset("a@b.c")
        val reset = JSONObject(seen.last().body)
        assertEquals("PASSWORD_RESET", reset.getString("requestType"))
        assertEquals("a@b.c", reset.getString("email"))
        reply = { error("EMAIL_NOT_FOUND") }
        try { g.sendPasswordReset("x@y.z"); fail() } catch (e: AuthException) { assertEquals("RESET_EMAIL_NOT_FOUND", e.code) }
    }

    // Firebase's codes as the desktop reads them; a bad key gets its own message
    @Test fun errorCodes() = runTest {
        val g = gateway()
        for ((message, code) in listOf(
            "EMAIL_EXISTS" to "EMAIL_EXISTS",
            "WEAK_PASSWORD : Password should be at least 6 characters" to "WEAK_PASSWORD",
            "INVALID_LOGIN_CREDENTIALS" to "INVALID_LOGIN_CREDENTIALS",
            "CONFIGURATION_NOT_FOUND" to "CONFIGURATION_NOT_FOUND",
            "API key not valid. Please pass a valid API key." to "INVALID_API_KEY",
        )) {
            reply = { error(message) }
            try { g.signIn("a@b.c", "secret1"); fail(message) } catch (e: AuthException) { assertEquals(message, code, e.code) }
        }
        reply = { 200 to "not json" }
        try { g.signIn("a@b.c", "secret1"); fail() } catch (e: AuthException) { assertEquals("NETWORK", e.code) }
    }

    @Test fun withoutAProjectNothingIsSent() = runTest {
        config = null
        try { gateway().signIn("a@b.c", "secret1"); fail() } catch (e: AuthException) { assertEquals("NOT_CONFIGURED", e.code) }
        assertTrue(seen.isEmpty())
    }
}
