package com.chaosnoteviewer

import android.app.Application
import com.chaosnoteviewer.account.AccountManager
import com.chaosnoteviewer.account.AuthGateway
import com.chaosnoteviewer.account.FirebaseAuthGateway
import com.chaosnoteviewer.data.AppDatabase
import com.chaosnoteviewer.data.NotesRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

// Holds the app-wide objects (no DI framework, the app is small).
class ChaosApp : Application() {
    val database by lazy { AppDatabase.open(this) }
    val notes by lazy { NotesRepository(database) }

    // null = this build has no Firebase (no google-services.json and not a debug build for the emulator)
    private val authGateway: AuthGateway? by lazy { createAuth() }
    val account by lazy { AccountManager(notes, authGateway) }

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
    }
}
