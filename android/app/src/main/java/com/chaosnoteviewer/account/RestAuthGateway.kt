package com.chaosnoteviewer.account

import com.chaosnoteviewer.crypto.KeyWrapper
import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

// Firebase Authentication over its REST API, the same calls as desktop Auth.js. No Firebase SDK: the app talks to
// whatever project the user entered ([config]), without google-services.json or an Android app registered there.
//
// The session is the refresh token: kept in sync_state (refresh_token), wrapped by the Android Keystore like the
// encryption key, plus who it belongs to (session_user) for starting offline. The id token (an hour) stays in memory.
class RestAuthGateway(
    private val config: () -> FirebaseConfig?,
    private val store: StateStore,
    private val wrapper: KeyWrapper,
    // writes of the session run here one after another, so a sign-out right before a sign-in can't land after it
    private val writes: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : AuthGateway {
    private class Session(val user: AuthUser, val refreshToken: String, val idToken: String, val expiresAt: Long)

    private val lock = Mutex()
    private var session: Session? = null
    private var loaded = false

    override suspend fun remembered(): AuthUser? = lock.withLock {
        loadLocked()
        session?.user
    }

    override suspend fun signUp(email: String, password: String): AuthUser = account("signUp", email, password)
    override suspend fun signIn(email: String, password: String): AuthUser = account("signInWithPassword", email, password)

    private suspend fun account(endpoint: String, email: String, password: String): AuthUser {
        val cfg = config() ?: throw AuthException("NOT_CONFIGURED")
        val body = JSONObject().put("email", email).put("password", password).put("returnSecureToken", true)
        val d = post("${cfg.authUrl}/accounts:$endpoint?key=${enc(cfg.apiKey)}", "application/json", body.toString())
        val id = d.getString("idToken")
        val s = Session(userOf(id, d.optString("localId"), d.optString("email", email)), d.getString("refreshToken"), id,
            now() + d.optString("expiresIn", "3600").toLong() * 1000)
        lock.withLock { remember(s) }
        return s.user
    }

    // A new id token: who the user is now, verified or not, account still there (USER_NOT_FOUND / USER_DISABLED otherwise).
    override suspend fun reload(): AuthUser? = lock.withLock {
        loadLocked()
        if (session == null) return@withLock null
        refreshLocked().user
    }

    override suspend fun idToken(forceRefresh: Boolean): String = lock.withLock {
        loadLocked()
        val s = session ?: throw AuthException("USER_NOT_FOUND", sessionLost = true)
        // a minute of margin, so a token does not expire on the way to Firestore
        if (!forceRefresh && s.idToken.isNotEmpty() && s.expiresAt - 60_000 > now()) s.idToken else refreshLocked().idToken
    }

    override suspend fun sendVerification() {
        val token = idToken(false)
        oob(JSONObject().put("requestType", "VERIFY_EMAIL").put("idToken", token), "INVALID_LOGIN_CREDENTIALS")
    }

    override suspend fun sendPasswordReset(email: String) =
        oob(JSONObject().put("requestType", "PASSWORD_RESET").put("email", email), "RESET_EMAIL_NOT_FOUND")

    override fun signOut() {
        session = null
        loaded = true
        writes.launch {
            store.setState(StateKeys.REFRESH_TOKEN, "")
            store.setState(StateKeys.SESSION_USER, "")
        }
    }

    // ---- the session ----

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val token = store.getState(StateKeys.REFRESH_TOKEN).takeIf { it.isNotEmpty() }
            ?.let { wrapper.unwrap(it)?.toString(Charsets.UTF_8) } ?: return
        val user = parseUser(store.getState(StateKeys.SESSION_USER)) ?: return
        session = Session(user, token, "", 0)
    }

    private fun remember(s: Session) {
        session = s
        loaded = true
        val blob = wrapper.wrap(s.refreshToken.toByteArray(Charsets.UTF_8))
        val user = JSONObject().put("uid", s.user.uid).put("email", s.user.email).put("verified", s.user.verified).toString()
        writes.launch {
            store.setState(StateKeys.REFRESH_TOKEN, blob)
            store.setState(StateKeys.SESSION_USER, user)
        }
    }

    private suspend fun refreshLocked(): Session {
        val s = session ?: throw AuthException("USER_NOT_FOUND", sessionLost = true)
        val cfg = config() ?: throw AuthException("NOT_CONFIGURED")
        val d = try {
            post("${cfg.tokenUrl}/token?key=${enc(cfg.apiKey)}", "application/x-www-form-urlencoded",
                "grant_type=refresh_token&refresh_token=${enc(s.refreshToken)}")
        } catch (e: AuthException) {
            throw if (e.code in SESSION_LOST) AuthException(e.code, sessionLost = true) else e
        }
        val id = d.getString("id_token")
        val fresh = Session(userOf(id, d.optString("user_id"), s.user.email), d.optString("refresh_token", s.refreshToken), id,
            now() + d.optString("expires_in", "3600").toLong() * 1000)
        if (fresh.refreshToken != s.refreshToken || fresh.user != s.user) remember(fresh) else session = fresh
        return fresh
    }

    private suspend fun oob(body: JSONObject, unknownUser: String) {
        val cfg = config() ?: throw AuthException("NOT_CONFIGURED")
        try {
            post("${cfg.authUrl}/accounts:sendOobCode?key=${enc(cfg.apiKey)}", "application/json", body.toString())
        } catch (e: AuthException) {
            throw if (e.code == "EMAIL_NOT_FOUND" || e.code == "USER_NOT_FOUND") AuthException(unknownUser) else e
        }
    }

    // ---- HTTP ----

    private suspend fun post(url: String, contentType: String, body: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val data = try { JSONTokener(text).nextValue() as? JSONObject } catch (e: JSONException) { null }
            when {
                data == null -> throw AuthException("NETWORK")
                status in 200..299 -> data
                else -> throw AuthException(errorCode(data))
            }
        } catch (e: IOException) {
            throw AuthException("NETWORK")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
        // the refresh token can't be used any more: sign in again (as opposed to being offline)
        val SESSION_LOST = setOf("TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND", "USER_DISABLED", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        // Firebase's code from an error answer: "EMAIL_EXISTS", "WEAK_PASSWORD : Password should be ..." -> the first word.
        // A wrong or restricted key comes from Google's API layer with a sentence instead of a code.
        fun errorCode(data: JSONObject): String {
            val err = data.optJSONObject("error") ?: return "NETWORK"
            val message = err.optString("message")
            val reason = err.optJSONArray("details")?.optJSONObject(0)?.optString("reason").orEmpty()
            return when {
                reason == "API_KEY_INVALID" || message.startsWith("API key not valid") -> "INVALID_API_KEY"
                reason.startsWith("API_KEY_") || err.optString("status") == "PERMISSION_DENIED" -> "API_KEY_BLOCKED"
                message.isEmpty() -> "UNKNOWN"
                else -> message.substringBefore(' ').substringBefore(':')
            }
        }

        // The claims of an id token (a JWT signed by Google; read here only to know who and whether verified,
        // the Firestore rules check them on the server).
        fun claims(idToken: String): JSONObject = try {
            JSONObject(String(Base64.getUrlDecoder().decode(idToken.split(".")[1].trimEnd('=')), Charsets.UTF_8))
        } catch (e: Exception) {
            JSONObject()
        }

        private fun userOf(idToken: String, uid: String, email: String): AuthUser {
            val c = claims(idToken)
            return AuthUser(
                uid = c.optString("user_id").ifEmpty { c.optString("sub") }.ifEmpty { uid },
                email = c.optString("email").ifEmpty { email },
                verified = c.optBoolean("email_verified", false),
            )
        }

        private fun parseUser(text: String): AuthUser? = try {
            val o = JSONObject(text)
            AuthUser(o.getString("uid"), o.getString("email"), o.optBoolean("verified"))
        } catch (e: JSONException) {
            null
        }
    }
}
