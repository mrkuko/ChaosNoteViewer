package com.chaosnoteviewer.account

import com.chaosnoteviewer.data.StateKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The part of the database the account rules touch (NotesRepository implements it).
interface AccountStore : com.chaosnoteviewer.data.StateStore {
    // notes written before any sign-in (author_id null) now belong to [uid]
    suspend fun claimNotes(uid: String)
    // the account these notes belonged to was deleted and the same email registered again
    suspend fun rebindNotes(oldUid: String, newUid: String)
}

sealed interface Account {
    data object SignedOut : Account
    // signed in at Firebase but the email is not verified yet: nothing is bound or stored until it is
    data class Pending(val email: String) : Account
    data class SignedIn(val email: String) : Account
}

data class AccountState(
    val account: Account = Account.SignedOut,
    val busy: Boolean = false,
    val error: String = "",
    val info: String = "",
)

// The app is local-first and works without an account; signing in is only needed to sync.
// Rules as in the desktop AppController: a database belongs to one account for good, so signing in as somebody
// else is refused; the first account to sign in claims the notes written before; a verified email is required.
// [auth] is null when this build has no Firebase configuration: there is nothing to sign in to.
class AccountManager(private val store: AccountStore, private val auth: AuthGateway?) {
    val configured: Boolean get() = auth != null

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private fun sameEmail(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    // Called at app start: picks up the session the Firebase SDK remembers. Offline keeps the session,
    // only a revoked one ends it. A user that is not verified yet goes (back) to waiting for it.
    suspend fun restore() {
        val gateway = auth ?: return
        val cached = gateway.currentUser ?: return
        val user = try {
            gateway.reload() ?: return
        } catch (e: AuthException) {
            if (e.sessionLost) {
                gateway.signOut()
                return
            }
            cached      // offline: trust what the SDK has
        }
        if (user.verified) startSession(user) else goPending(user, "")
    }

    suspend fun authenticate(create: Boolean, email: String, password: String) {
        val gateway = auth ?: return
        val mail = email.trim()
        if (mail.isEmpty()) return fail("MISSING_EMAIL")
        if (password.isEmpty()) return fail("MISSING_PASSWORD")
        val boundEmail = store.getState(StateKeys.EMAIL)
        if (create && store.getState(StateKeys.UID).isNotEmpty() && !sameEmail(mail, boundEmail)) {
            _state.update { it.copy(error = "The notes on this device belong to $boundEmail, so a new account can't be created here.", info = "") }
            return
        }
        _state.update { it.copy(busy = true, error = "", info = "") }
        try {
            val user = if (create) gateway.signUp(mail, password) else gateway.signIn(mail, password)
            if (user.verified) {
                startSession(user)
            } else {
                goPending(user, "")
                if (create) resendVerification()
                else _state.update { it.copy(info = "${user.email} is not verified yet. Open the link from the verification email, then press Continue.") }
            }
        } catch (e: AuthException) {
            _state.update { it.copy(error = e.message.orEmpty()) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    // Asks Firebase again; once the link in the email was opened the user comes back verified.
    suspend fun checkVerified() {
        val gateway = auth ?: return
        if (_state.value.account !is Account.Pending) return
        _state.update { it.copy(busy = true, error = "", info = "") }
        try {
            val user = gateway.reload()
            when {
                user == null -> _state.update { it.copy(account = Account.SignedOut) }
                user.verified -> startSession(user)
                else -> _state.update { it.copy(error = AuthErrors.text("NOT_VERIFIED")) }
            }
        } catch (e: AuthException) {
            _state.update { it.copy(error = e.message.orEmpty()) }
            if (e.sessionLost) _state.update { it.copy(account = Account.SignedOut) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    suspend fun resendVerification() {
        val gateway = auth ?: return
        val pending = _state.value.account as? Account.Pending ?: return
        _state.update { it.copy(busy = true, error = "") }
        try {
            gateway.sendVerification()
            _state.update { it.copy(info = "We sent a verification link to ${pending.email}. Open it, then press Continue. Check your spam folder too.") }
        } catch (e: AuthException) {
            _state.update { it.copy(error = e.message.orEmpty()) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun cancelPending() {
        auth?.signOut()
        _state.value = AccountState()
    }

    suspend fun resetPassword(email: String) {
        val gateway = auth ?: return
        val mail = email.trim()
        if (mail.isEmpty()) return fail("MISSING_EMAIL")
        _state.update { it.copy(busy = true, error = "", info = "") }
        try {
            gateway.sendPasswordReset(mail)
            _state.update { it.copy(info = "Password reset email sent to $mail. Check your spam folder too.") }
        } catch (e: AuthException) {
            _state.update { it.copy(error = e.message.orEmpty()) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    // The notes stay bound to the account (uid and email stay in the database), only the sign-in ends.
    fun signOut() {
        auth?.signOut()
        _state.value = AccountState()
    }

    // The sign-in can't be used any more (the account was deleted or disabled, the token revoked): back to signed out,
    // with a message. The notes stay on this device, bound to the account.
    fun sessionEnded() {
        auth?.signOut()
        _state.value = AccountState(error = "You were signed out because your session ended. Sign in again.")
    }

    private fun fail(code: String) = _state.update { it.copy(error = AuthErrors.text(code), info = "") }

    private fun goPending(user: AuthUser, info: String) =
        _state.update { it.copy(account = Account.Pending(user.email), info = info, error = "") }

    // Binds this device's notes to a verified account.
    private suspend fun startSession(user: AuthUser): Boolean {
        val bound = store.getState(StateKeys.UID)
        if (bound.isNotEmpty() && bound != user.uid) {
            // Same email, different uid: emails are unique in Firebase, so the account these notes belonged
            // to was deleted and this verified one replaces it. Anyone else is refused.
            val boundEmail = store.getState(StateKeys.EMAIL)
            if (!sameEmail(user.email, boundEmail)) {
                auth?.signOut()
                _state.value = AccountState(error = "The notes on this device belong to $boundEmail. Sign in with that account.")
                return false
            }
            store.rebindNotes(bound, user.uid)
        }
        store.setState(StateKeys.UID, user.uid)
        store.setState(StateKeys.EMAIL, user.email)
        store.claimNotes(user.uid)
        _state.value = AccountState(account = Account.SignedIn(user.email))
        return true
    }
}
