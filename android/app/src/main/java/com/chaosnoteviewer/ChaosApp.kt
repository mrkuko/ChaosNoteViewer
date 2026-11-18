package com.chaosnoteviewer

import android.app.Application
import com.chaosnoteviewer.account.AccountManager
import com.chaosnoteviewer.account.AuthException
import com.chaosnoteviewer.account.AuthGateway
import com.chaosnoteviewer.account.FirebaseConfig
import com.chaosnoteviewer.account.FirebaseSettings
import com.chaosnoteviewer.account.RestAuthGateway
import com.chaosnoteviewer.data.StateKeys
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

// Holds the app-wide objects (no DI framework, the app is small).
class ChaosApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val extrasPrefs by lazy { ExtrasPrefs(notes) }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // once: read the notes again so the stars set on other devices arrive (they were dropped before this version)
        appScope.launch { runCatching { notes.repairStarsOnce() } }
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
            firebase.load()                           // the user's own Firebase project, if they entered one
            account.restore()                         // the remembered sign-in
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

    // The user's own Firebase project (entered on first start or in Settings); a debug build for the local emulator
    // (firebase.emulator=true in local.properties) has the emulator's built in instead.
    val firebase by lazy {
        FirebaseSettings(
            notes,
            fixed = if (BuildConfig.FIREBASE_EMULATOR) FirebaseConfig.EMULATOR else null,
            // another project: its cloud copy is new, so everything is uploaded again and the key is set up again
            onProjectChanged = {
                notes.forgetRemote()
                notes.clearState(StateKeys.ENC_KEY)
                notes.clearState(StateKeys.KEY_CONFIRMED)
            },
        )
    }

    // the session is saved one write after another, off the main thread
    private val authWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val authGateway: AuthGateway by lazy {
        RestAuthGateway(config = { firebase.current }, store = notes, wrapper = AndroidKeystoreWrapper("chaosnoteviewer_session_wrap"), writes = authWrites)
    }
    val account by lazy { AccountManager(notes, authGateway, configured = { firebase.current != null }) }

    // the cloud copy of the signed-in account (Firestore over REST), in the project the user entered
    private val remote: RemoteStore by lazy {
        val gateway = authGateway
        RestFirestore(
            baseUrl = { firebase.current?.firestoreUrl ?: "https://firestore.googleapis.com/v1" },
            projectId = { firebase.current?.projectId.orEmpty() },
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
}
