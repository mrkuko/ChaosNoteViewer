package com.chaosnoteviewer.account

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthEmailException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

// Firebase Auth through the official Android SDK. The SDK keeps the signed-in user and its refresh token
// itself (encrypted app storage), so nothing of that goes into our database.
class FirebaseAuthGateway(private val auth: FirebaseAuth) : AuthGateway {

    override val currentUser: AuthUser? get() = auth.currentUser?.toUser()

    override suspend fun signUp(email: String, password: String): AuthUser = call {
        auth.createUserWithEmailAndPassword(email, password).await().user!!.toUser()
    }

    override suspend fun signIn(email: String, password: String): AuthUser = call {
        auth.signInWithEmailAndPassword(email, password).await().user!!.toUser()
    }

    override suspend fun reload(): AuthUser? {
        val user = auth.currentUser ?: return null
        return call(sessionLostOnUnknownUser = true) {
            user.reload().await()
            user.getIdToken(true).await()       // the Firestore rules read email_verified from this token
            auth.currentUser?.toUser()
        }
    }

    override suspend fun sendVerification() = call {
        (auth.currentUser ?: throw AuthException("USER_NOT_FOUND", sessionLost = true)).sendEmailVerification().await()
        Unit
    }

    override suspend fun sendPasswordReset(email: String) = call(unknownUser = "RESET_EMAIL_NOT_FOUND") {
        auth.sendPasswordResetEmail(email).await()
        Unit
    }

    override fun signOut() = auth.signOut()

    private fun FirebaseUser.toUser() = AuthUser(uid = uid, email = email.orEmpty(), verified = isEmailVerified)

    // Runs an SDK call and turns its exceptions into AuthException with the same codes as the desktop app.
    private suspend fun <T> call(
        unknownUser: String = "INVALID_LOGIN_CREDENTIALS",
        sessionLostOnUnknownUser: Boolean = false,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthException) {
        throw e
    } catch (e: Exception) {
        throw map(e, unknownUser, sessionLostOnUnknownUser)
    }

    private fun map(e: Exception, unknownUser: String, sessionLostOnUnknownUser: Boolean): AuthException = when (e) {
        is FirebaseAuthUserCollisionException -> AuthException("EMAIL_EXISTS")
        is FirebaseAuthWeakPasswordException -> AuthException("WEAK_PASSWORD")
        is FirebaseAuthEmailException -> AuthException("INVALID_EMAIL")
        is FirebaseAuthInvalidCredentialsException ->
            AuthException(if (e.errorCode == "ERROR_INVALID_EMAIL") "INVALID_EMAIL" else "INVALID_LOGIN_CREDENTIALS")
        is FirebaseAuthInvalidUserException -> when (e.errorCode) {
            "ERROR_USER_DISABLED" -> AuthException("USER_DISABLED", sessionLost = sessionLostOnUnknownUser)
            "ERROR_USER_TOKEN_EXPIRED" -> AuthException("USER_NOT_FOUND", sessionLost = true)
            else -> AuthException(unknownUser, sessionLost = sessionLostOnUnknownUser)
        }
        is FirebaseTooManyRequestsException -> AuthException("TOO_MANY_ATTEMPTS_TRY_LATER")
        is FirebaseNetworkException -> AuthException("NETWORK")
        is FirebaseAuthException -> AuthException(e.errorCode.removePrefix("ERROR_"))
        // offline and similar IO failures arrive as other exceptions
        else -> AuthException("NETWORK")
    }
}
