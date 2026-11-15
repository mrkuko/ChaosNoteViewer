package com.chaosnoteviewer.account

import com.chaosnoteviewer.data.StateKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : AccountStore {
    val state = mutableMapOf<String, String>()
    var claimed: String? = null
    var rebound: Pair<String, String>? = null
    override suspend fun getState(key: String) = state[key] ?: ""
    override suspend fun setState(key: String, value: String) { state[key] = value }
    override suspend fun claimNotes(uid: String) { claimed = uid }
    override suspend fun rebindNotes(oldUid: String, newUid: String) { rebound = oldUid to newUid }
}

// accounts: email -> (uid, password, verified)
private class FakeAuth : AuthGateway {
    class Acc(val uid: String, val password: String, var verified: Boolean)
    val accounts = mutableMapOf<String, Acc>()
    var signedIn: String? = null
    var offline = false
    var lost = false
    var verificationsSent = 0
    var resetsSent = mutableListOf<String>()

    private fun user(email: String) = accounts[email]!!.let { AuthUser(it.uid, email, it.verified) }

    override val currentUser get() = signedIn?.let { user(it) }

    override suspend fun signUp(email: String, password: String): AuthUser {
        if (offline) throw AuthException("NETWORK")
        if (email in accounts) throw AuthException("EMAIL_EXISTS")
        if (password.length < 6) throw AuthException("WEAK_PASSWORD")
        accounts[email] = Acc("uid-${accounts.size + 1}", password, false)
        signedIn = email
        return user(email)
    }

    override suspend fun signIn(email: String, password: String): AuthUser {
        if (offline) throw AuthException("NETWORK")
        val acc = accounts[email]
        if (acc == null || acc.password != password) throw AuthException("INVALID_LOGIN_CREDENTIALS")
        signedIn = email
        return user(email)
    }

    override suspend fun reload(): AuthUser? {
        if (offline) throw AuthException("NETWORK")
        if (lost) throw AuthException("USER_NOT_FOUND", sessionLost = true)
        return signedIn?.let { user(it) }
    }

    override suspend fun idToken(forceRefresh: Boolean) = "token-${signedIn ?: "nobody"}"
    override suspend fun sendVerification() { verificationsSent++ }
    override suspend fun sendPasswordReset(email: String) {
        if (email !in accounts) throw AuthException("RESET_EMAIL_NOT_FOUND")
        resetsSent += email
    }
    override fun signOut() { signedIn = null }
}

class AccountManagerTest {
    private val store = FakeStore()
    private val auth = FakeAuth()
    private val manager = AccountManager(store, auth)
    private val account get() = manager.state.value.account

    private fun existing(email: String, verified: Boolean = true, uid: String = "uid-x") {
        auth.accounts[email] = FakeAuth.Acc(uid, "secret1", verified)
    }

    @Test fun notConfiguredDoesNothing() = runTest {
        val m = AccountManager(store, null)
        assertFalse(m.configured)
        m.authenticate(false, "a@b.c", "secret1")
        assertEquals(Account.SignedOut, m.state.value.account)
    }

    @Test fun signInVerifiedBindsTheDatabaseAndClaimsNotes() = runTest {
        existing("a@b.c")
        manager.authenticate(false, "a@b.c", "secret1")
        assertEquals(Account.SignedIn("a@b.c"), account)
        assertEquals("uid-x", store.state[StateKeys.UID])
        assertEquals("a@b.c", store.state[StateKeys.EMAIL])
        assertEquals("uid-x", store.claimed)
        assertEquals("", manager.state.value.error)
    }

    @Test fun signInWithWrongPassword() = runTest {
        existing("a@b.c")
        manager.authenticate(false, "a@b.c", "nope")
        assertEquals(Account.SignedOut, account)
        assertEquals("Wrong email or password.", manager.state.value.error)
        assertNull(store.claimed)
    }

    @Test fun emptyFieldsAreRejectedBeforeAnyRequest() = runTest {
        manager.authenticate(false, " ", "x")
        assertEquals("Enter your email.", manager.state.value.error)
        manager.authenticate(false, "a@b.c", "")
        assertEquals("Enter your password.", manager.state.value.error)
        assertNull(auth.signedIn)
    }

    @Test fun createAccountWaitsForVerificationAndBindsNothing() = runTest {
        manager.authenticate(true, "new@b.c", "secret1")
        assertEquals(Account.Pending("new@b.c"), account)
        assertEquals(1, auth.verificationsSent)
        assertTrue(manager.state.value.info.contains("verification link"))
        assertEquals("", store.state[StateKeys.UID] ?: "")       // nothing bound until verified
        assertNull(store.claimed)
    }

    @Test fun continueBeforeVerifyingKeepsWaiting() = runTest {
        manager.authenticate(true, "new@b.c", "secret1")
        manager.checkVerified()
        assertEquals(Account.Pending("new@b.c"), account)
        assertEquals("The email is not verified yet. Open the link in the email first.", manager.state.value.error)
    }

    @Test fun continueAfterVerifyingSignsIn() = runTest {
        manager.authenticate(true, "new@b.c", "secret1")
        auth.accounts["new@b.c"]!!.verified = true             // the link in the email was opened
        manager.checkVerified()
        assertEquals(Account.SignedIn("new@b.c"), account)
        assertEquals("uid-1", store.claimed)
    }

    @Test fun signInWithUnverifiedAccountIsPendingWithoutNewEmail() = runTest {
        existing("a@b.c", verified = false)
        manager.authenticate(false, "a@b.c", "secret1")
        assertEquals(Account.Pending("a@b.c"), account)
        assertEquals(0, auth.verificationsSent)
        assertTrue(manager.state.value.info.contains("is not verified yet"))
    }

    @Test fun resendSendsAnotherEmail() = runTest {
        manager.authenticate(true, "new@b.c", "secret1")
        manager.resendVerification()
        assertEquals(2, auth.verificationsSent)
    }

    @Test fun cancelPendingSignsOut() = runTest {
        manager.authenticate(true, "new@b.c", "secret1")
        manager.cancelPending()
        assertEquals(Account.SignedOut, account)
        assertNull(auth.signedIn)
    }

    @Test fun createExistingEmailFails() = runTest {
        existing("a@b.c")
        manager.authenticate(true, "a@b.c", "secret1")
        assertEquals("An account with this email already exists.", manager.state.value.error)
    }

    @Test fun databaseBelongsToOneAccount_otherAccountIsRefused() = runTest {
        store.state[StateKeys.UID] = "uid-1"
        store.state[StateKeys.EMAIL] = "owner@b.c"
        existing("other@b.c", uid = "uid-2")
        manager.authenticate(false, "other@b.c", "secret1")
        assertEquals(Account.SignedOut, account)
        assertEquals("The notes on this device belong to owner@b.c. Sign in with that account.", manager.state.value.error)
        assertNull(auth.signedIn)                                // the refused sign-in is undone
        assertEquals("uid-1", store.state[StateKeys.UID])
        assertNull(store.claimed)
    }

    @Test fun createAccountWithAnotherEmailIsRefusedUpFront() = runTest {
        store.state[StateKeys.UID] = "uid-1"
        store.state[StateKeys.EMAIL] = "owner@b.c"
        manager.authenticate(true, "other@b.c", "secret1")
        assertEquals("The notes on this device belong to owner@b.c, so a new account can't be created here.", manager.state.value.error)
        assertTrue(auth.accounts.isEmpty())
    }

    @Test fun sameEmailIgnoresCaseAndSpaces() = runTest {
        store.state[StateKeys.UID] = "uid-1"
        store.state[StateKeys.EMAIL] = "Owner@B.c"
        existing("owner@b.c", uid = "uid-1")
        manager.authenticate(false, " owner@b.c ", "secret1")
        assertEquals(Account.SignedIn("owner@b.c"), account)
    }

    @Test fun sameEmailNewUidRebindsNotes() = runTest {
        store.state[StateKeys.UID] = "old-uid"
        store.state[StateKeys.EMAIL] = "owner@b.c"
        existing("owner@b.c", uid = "new-uid")                   // the old account was deleted and registered again
        manager.authenticate(false, "owner@b.c", "secret1")
        assertEquals("old-uid" to "new-uid", store.rebound)
        assertEquals("new-uid", store.state[StateKeys.UID])
        assertEquals(Account.SignedIn("owner@b.c"), account)
    }

    @Test fun signOutKeepsTheBinding() = runTest {
        existing("a@b.c")
        manager.authenticate(false, "a@b.c", "secret1")
        manager.signOut()
        assertEquals(Account.SignedOut, account)
        assertEquals("uid-x", store.state[StateKeys.UID])
        assertEquals("a@b.c", store.state[StateKeys.EMAIL])
    }

    @Test fun restoreSignsInTheRememberedUser() = runTest {
        existing("a@b.c"); auth.signedIn = "a@b.c"
        manager.restore()
        assertEquals(Account.SignedIn("a@b.c"), account)
    }

    @Test fun restoreOfflineKeepsTheSession() = runTest {
        existing("a@b.c"); auth.signedIn = "a@b.c"; auth.offline = true
        manager.restore()
        assertEquals(Account.SignedIn("a@b.c"), account)
        assertEquals("a@b.c", auth.signedIn)
    }

    @Test fun restoreOfRevokedSessionSignsOut() = runTest {
        existing("a@b.c"); auth.signedIn = "a@b.c"; auth.lost = true
        manager.restore()
        assertEquals(Account.SignedOut, account)
        assertNull(auth.signedIn)
    }

    @Test fun restoreOfUnverifiedUserWaitsForVerification() = runTest {
        existing("a@b.c", verified = false); auth.signedIn = "a@b.c"
        manager.restore()
        assertEquals(Account.Pending("a@b.c"), account)
        assertNull(store.claimed)
    }

    @Test fun restoreWithNobodyStaysSignedOut() = runTest {
        manager.restore()
        assertEquals(Account.SignedOut, account)
    }

    @Test fun offlineSignInShowsNetworkError() = runTest {
        existing("a@b.c"); auth.offline = true
        manager.authenticate(false, "a@b.c", "secret1")
        assertEquals("Could not reach Firebase. Check your connection.", manager.state.value.error)
        assertFalse(manager.state.value.busy)
    }

    @Test fun passwordReset() = runTest {
        existing("a@b.c")
        manager.resetPassword("a@b.c")
        assertEquals(listOf("a@b.c"), auth.resetsSent)
        assertTrue(manager.state.value.info.startsWith("Password reset email sent to a@b.c"))
        manager.resetPassword("ghost@b.c")
        assertEquals("No account with this email.", manager.state.value.error)
        manager.resetPassword("")
        assertEquals("Enter your email.", manager.state.value.error)
    }
}
