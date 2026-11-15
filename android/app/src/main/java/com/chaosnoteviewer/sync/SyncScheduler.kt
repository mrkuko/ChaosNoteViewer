package com.chaosnoteviewer.sync

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.data.NotesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// When the sync runs without the user pressing the button. Same rhythm as the desktop app:
//  - 2 s after a change made here (a burst of changes = one sync),
//  - every 30 s while the app is open, every 15 minutes while it is closed (WorkManager, only with a network),
//  - slower after failures, up to 64 times as slow, and back to normal after the first success,
//  - and whenever the app comes to the foreground.
// A change made while offline is sent as soon as there is a network again, even if the app was closed meanwhile.
class SyncScheduler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notes: NotesRepository,
    private val sync: SyncManager,
) {
    companion object {
        const val PUSH_DELAY_MS = 2_000L
        const val FOREGROUND_POLL_MS = 30_000L
        // 15 minutes is the shortest wait WorkManager allows for a periodic job
        const val BACKGROUND_POLL_MINUTES = 15L
        const val WORK_PERIODIC = "background-sync"
        const val WORK_SOON = "sync-soon"

        // The wait before the next poll: doubled after each consecutive failure (see SyncManager.MAX_FAILURES).
        fun pollInterval(baseMs: Long, failures: Int): Long = baseMs shl failures.coerceIn(0, SyncManager.MAX_FAILURES)

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        // While the app is closed: about every 15 minutes, only with a network. Calling it again (every app start)
        // keeps the job that is there, so the wait is never reset.
        fun scheduleBackground(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(BACKGROUND_POLL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelBackground(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(WORK_PERIODIC)
            work.cancelUniqueWork(WORK_SOON)
        }

        // Sends changes as soon as the network is there. WorkManager remembers it across app restarts.
        fun syncWhenOnline(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(SyncWorker.ONLY_IF_DIRTY to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_SOON, ExistingWorkPolicy.KEEP, request)
        }
    }

    // Sync soon after a change made on this device. The trigger is the number of notes waiting to be synced: a pull
    // writes notes that are not dirty, so it never starts another sync; a sync that fails leaves them dirty and the
    // network worker (and the polling) tries again later.
    @OptIn(FlowPreview::class)
    fun startPushOnChange(backgroundEnabled: suspend () -> Boolean) {
        scope.launch {
            notes.dirtyCount
                .map { it > 0 }
                .distinctUntilChanged()
                .filter { it }
                .debounce(PUSH_DELAY_MS)
                .collect {
                    // an unexpected error must not take the whole app down (this runs in the app's own scope)
                    try {
                        sync.syncNow()
                        // still dirty (offline, refused, key not ready): let the system send it when it can
                        if (notes.dirtyCountNow() > 0 && backgroundEnabled()) syncWhenOnline(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SyncScheduler", "sync after a change failed", e)
                    }
                }
        }
    }

    // While the app is on the screen: sync now, then poll. Stops when the app goes to the background.
    // [lifecycle] is the process lifecycle (ProcessLifecycleOwner).
    fun startForegroundPolling(lifecycle: Lifecycle) {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try {
                        sync.syncOrStart()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SyncScheduler", "poll failed", e)
                    }
                    delay(pollInterval(FOREGROUND_POLL_MS, sync.state.value.failures))
                }
            }
        }
    }
}

// The work done in the background: sync (or find out about the key when the start was offline).
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val ONLY_IF_DIRTY = "only_if_dirty"
        private const val MAX_ATTEMPTS = 6
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as ChaosApp
        if (!app.extrasPrefs.backgroundSync()) return Result.success()
        app.awaitSignIn()
        val onlyIfDirty = inputData.getBoolean(ONLY_IF_DIRTY, false)
        if (onlyIfDirty && app.notes.dirtyCountNow() == 0) return Result.success()     // the app already sent it
        app.sync.syncOrStart()
        // the one-off job for changes made offline tries again (with growing waits) until the changes are sent
        val unsent = onlyIfDirty && app.notes.dirtyCountNow() > 0 && app.sync.state.value.signedIn
        return if (unsent && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }
}
