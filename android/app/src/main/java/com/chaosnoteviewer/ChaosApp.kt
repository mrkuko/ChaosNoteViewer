package com.chaosnoteviewer

import android.app.Application
import com.chaosnoteviewer.account.AccountManager
import com.chaosnoteviewer.account.AuthException
import com.chaosnoteviewer.account.AuthGateway
import com.chaosnoteviewer.account.FirebaseAuthGateway
import com.chaosnoteviewer.crypto.AndroidKeystoreWrapper
import com.chaosnoteviewer.crypto.KeyStorage
import com.chaosnoteviewer.data.AppDatabase
import com.chaosnoteviewer.data.NotesRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.chaosnoteviewer.sync.RemoteStore
import com.chaosnoteviewer.sync.RestFirestore
import com.chaosnoteviewer.sync.SyncException
import com.chaosnoteviewer.sync.SyncManager
import com.google.firebase.auth.FirebaseAuth

// Holds the app-wide objects (no DI framework, the app is small).
class ChaosApp : Application() {
    val database by lazy { AppDatabase.open(this) }
    val notes by lazy { NotesRepository(database) }

    // null = this build has no Firebase (no google-services.json and not a debug build for the emulator)
    private val authGateway: AuthGateway? by lazy { createAuth() }
    val account by lazy { AccountManager(notes, authGateway) }

    // the cloud copy of the signed-in account (Firestore over REST), null without Firebase
    private val remote: RemoteStore? by lazy {
        val gateway = authGateway ?: return@lazy null
        RestFirestore(
            baseUrl = if (BuildConfig.FIREBASE_EMULATOR) "http://$EMULATOR_HOST:$EMULATOR_FIRESTORE_PORT/v1" else "https://firestore.googleapis.com/v1",
            projectId = FirebaseApp.getInstance().options.projectId ?: "demo-chaosnoteviewer",
            token = { refresh ->
                try {
                    gateway.idToken(refresh)
                } catch (e: AuthException) {
                    // signed out or revoked: the server would say the same
                    throw SyncException(if (e.sessionLost) "UNAUTHENTICATED" else e.code, e.message.orEmpty())
                }
            },
        )
    }
    val sync by lazy { SyncManager(notes, remote, KeyStorage(notes, AndroidKeystoreWrapper())) }

    private fun createAuth(): AuthGateway? {
        // with google-services.json the Firebase plugin already initialized the real project
        if (FirebaseApp.getApps(this).isEmpty()) {
            if (!BuildConfig.FIREBASE_EMULATOR)
                return null
            // no real project: the emulator accepts any key, the id matches `npm run emulators` in core/firebase
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey("demo-key")
                    .setApplicationId("1:0:android:0000000000000000")
                    .setProjectId("demo-chaosnoteviewer")
                    .build(),
            )
        }
        val auth = FirebaseAuth.getInstance()
        if (BuildConfig.FIREBASE_EMULATOR)
            auth.useEmulator(EMULATOR_HOST, EMULATOR_AUTH_PORT)
        return FirebaseAuthGateway(auth)
    }

    companion object {
        // the PC's emulator through `adb reverse tcp:43099 tcp:43099` (ports from core/firebase/firebase.json)
        const val EMULATOR_HOST = "localhost"
        const val EMULATOR_AUTH_PORT = 43099
        const val EMULATOR_FIRESTORE_PORT = 43080
    }
}
