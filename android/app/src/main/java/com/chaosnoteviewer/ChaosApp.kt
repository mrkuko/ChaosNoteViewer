package com.chaosnoteviewer

import android.app.Application
import com.chaosnoteviewer.account.AccountManager
import com.chaosnoteviewer.account.AuthException
import com.chaosnoteviewer.account.AuthGateway
import com.chaosnoteviewer.account.FirebaseAuthGateway
import com.chaosnoteviewer.crypto.AndroidKeystoreWrapper
import com.chaosnoteviewer.extras.DeadlineWorker
import com.chaosnoteviewer.extras.Extras
import com.chaosnoteviewer.extras.ExtrasPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import com.chaosnoteviewer.crypto.KeyStorage
import com.chaosnoteviewer.data.AppDatabase
import com.chaosnoteviewer.data.NotesRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.chaosnoteviewer.sync.RemoteStore
import com.chaosnoteviewer.sync.RestFirestore
import com.chaosnoteviewer.sync.SyncException
import com.chaosnoteviewer.sync.SyncManager
import com.chaosnoteviewer.sync.SyncScheduler
import androidx.lifecycle.ProcessLifecycleOwner
import com.chaosnoteviewer.account.Account
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import com.google.firebase.auth.FirebaseAuth

// Holds the app-wide objects (no DI framework, the app is small).
class ChaosApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val extrasPrefs by lazy { ExtrasPrefs(notes) }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // the permanent "N open notes" notification of older versions is gone
        com.chaosnoteviewer.extras.Notifications.removeLegacy(this)
        // the widget and the showing reminders follow the notes (a burst of changes = one update)
        appScope.launch {
            notes.alive.debounce(400).collect { runCatching { Extras.refresh(this@ChaosApp, extrasPrefs, it) } }
        }
        // the daily deadline check is realigned to 8:00 at every start
        appScope.launch { if (extrasPrefs.reminders()) DeadlineWorker.schedule(this@ChaosApp) }

        // The account and the sync live here, not in a screen: a background job must find them as well.
        appScope.launch {
            account.restore()                         // the sign-in the Firebase SDK remembers
            restored.complete(Unit)
        }
        appScope.launch {
            // Only after the remembered sign-in was looked at: before that everybody looks signed out, and cancelling the
            // background jobs then would stop the very job that started this process.
            restored.await()
            account.state.map { it.account is Account.SignedIn }.distinctUntilChanged().collect { signedIn ->
                sync.onSignedIn(signedIn)
                if (!signedIn) SyncScheduler.cancelBackground(this@ChaosApp)
                else if (extrasPrefs.backgroundSync()) SyncScheduler.scheduleBackground(this@ChaosApp)
            }
        }
        scheduler.startPushOnChange { extrasPrefs.backgroundSync() }
        // polling needs the main thread's lifecycle of the whole app (foreground = some screen is visible)
        android.os.Handler(mainLooper).post { scheduler.startForegroundPolling(ProcessLifecycleOwner.get().lifecycle) }
    }

    private val restored = CompletableDeferred<Unit>()

    // For a background job: the remembered sign-in is restored and the sync knows about it (or there is none).
    suspend fun awaitSignIn() {
        restored.await()
        if (account.state.value.account is Account.SignedIn)
            withTimeoutOrNull(15_000) { sync.state.first { it.signedIn } }
    }

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
                    // the account is gone or disabled: the sync signs out (not the same as being offline)
                    throw SyncException(if (e.sessionLost) "SESSION_LOST" else e.code, e.message.orEmpty())
                }
            },
        )
    }
    val sync by lazy {
        SyncManager(notes, remote, KeyStorage(notes, AndroidKeystoreWrapper()), onSessionLost = { account.sessionEnded() })
    }
    val scheduler by lazy { SyncScheduler(this, appScope, notes, sync) }

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
