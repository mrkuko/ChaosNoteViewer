package com.chaosnoteviewer.account

// What the account rules need from Firebase Auth. The real thing is FirebaseAuthGateway; tests use a fake.

data class AuthUser(val uid: String, val email: String, val verified: Boolean)

// code: Firebase's own wording as on desktop (EMAIL_EXISTS, WEAK_PASSWORD, NETWORK, ...).
// sessionLost: the sign-in can't be used any more (account deleted/disabled, token revoked); offline is not that.
class AuthException(val code: String, val sessionLost: Boolean = false) : Exception(AuthErrors.text(code))

interface AuthGateway {
    // the signed-in user the SDK remembers from the last run, null = nobody. Not verified yet means "pending".
    val currentUser: AuthUser?

    suspend fun signUp(email: String, password: String): AuthUser
    suspend fun signIn(email: String, password: String): AuthUser

    // asks the server for the user again (email verified? account still there?) and refreshes the id token
    suspend fun reload(): AuthUser?

    suspend fun sendVerification()
    suspend fun sendPasswordReset(email: String)
    fun signOut()
}

// Same texts as Auth.js errorText on desktop.
object AuthErrors {
    fun text(code: String): String = when (code) {
        "EMAIL_EXISTS" -> "An account with this email already exists."
        "INVALID_EMAIL" -> "This email address is not valid."
        "MISSING_EMAIL" -> "Enter your email."
        "MISSING_PASSWORD" -> "Enter your password."
        "WEAK_PASSWORD" -> "The password must have at least 6 characters."
        "INVALID_LOGIN_CREDENTIALS" -> "Wrong email or password."
        "USER_DISABLED" -> "This account has been disabled."
        "TOO_MANY_ATTEMPTS_TRY_LATER" -> "Too many attempts, try again later."
        "OPERATION_NOT_ALLOWED" -> "Email/password sign-in is not enabled for this Firebase project."
        "RESET_EMAIL_NOT_FOUND" -> "No account with this email."
        "NOT_VERIFIED" -> "The email is not verified yet. Open the link in the email first."
        "NETWORK" -> "Could not reach Firebase. Check your connection."
        else -> "Sign-in failed ($code)."
    }
}
