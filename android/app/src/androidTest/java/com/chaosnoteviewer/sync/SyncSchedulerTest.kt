package com.chaosnoteviewer.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.chaosnoteviewer.crypto.FakeWrapper
import com.chaosnoteviewer.crypto.KeyStorage
import com.chaosnoteviewer.data.AppDatabase
import com.chaosnoteviewer.data.NotesRepository
import com.chaosnoteviewer.data.StateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// The triggers of the sync (real database, real clock): after a change, and the jobs handed to WorkManager.
@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val uid = "user-1"
    private val cloud = FakeRemoteStore()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repo = NotesRepository(db)
    private val sync = SyncManager(repo, cloud, KeyStorage(repo, FakeWrapper()))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val work = WorkManager.getInstance(context)

    @After fun close() {
        // wait until the scheduler's coroutines are really gone: they must not touch a closed database
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
        db.close()
        SyncScheduler.cancelBackground(context)
    }

    private suspend fun signedInAndReady() {
        repo.setState(StateKeys.UID, uid)
        sync.onSignedIn(true)
        sync.confirmKey()
    }

    private suspend fun eventually(timeoutMs: Long = 8_000, condition: suspend () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            delay(100)
        }
        return condition()
    }

    private fun states(name: String): List<WorkInfo.State> = work.getWorkInfosForUniqueWork(name).get().map { it.state }

    // ---- sync soon after a change ----

    @Test fun aChangeIsSyncedAboutTwoSecondsLater() = runBlocking {
        signedInAndReady()
        SyncScheduler(context, scope, repo, sync).startPushOnChange { false }
        val started = System.currentTimeMillis()
        repo.add("typed on the phone")
        assertTrue("it was sent without anybody pressing a button", eventually { cloud.docIds(uid).size == 1 })
        val waited = System.currentTimeMillis() - started
        assertTrue("not at once, a burst of changes is one sync (waited $waited ms)", waited >= SyncScheduler.PUSH_DELAY_MS - 200)
    }

    @Test fun aBurstOfChangesIsOneSync() = runBlocking {
        signedInAndReady()
        SyncScheduler(context, scope, repo, sync).startPushOnChange { false }
        repeat(5) { repo.add("note $it"); delay(200) }
        assertTrue(eventually { cloud.docIds(uid).size == 5 })
        assertEquals("all five in one request", 1, cloud.uploads)
    }

    @Test fun aPullDoesNotStartAnotherSync() = runBlocking {
        signedInAndReady()
        // a note written on another phone
        val other = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val otherRepo = NotesRepository(other)
            otherRepo.add("from the other phone")
            SyncEngine(otherRepo, cloud).run(uid, sync.let { KeyStorage(repo, FakeWrapper()).load()!! })
        } finally { other.close() }
        SyncScheduler(context, scope, repo, sync).startPushOnChange { false }
        val before = cloud.uploads
        sync.syncNow()                                           // pulls the note: written as not dirty
        delay(3_000)
        assertEquals(1, repo.alive.first().size)
        assertEquals("a pulled note is not a change to send", before, cloud.uploads)
    }

    @Test fun nothingIsSentWhenSignedOut() = runBlocking {
        SyncScheduler(context, scope, repo, sync).startPushOnChange { false }
        repo.add("only local")
        delay(3_000)
        assertEquals(0, cloud.uploads)
    }

    @Test fun whatCouldNotBeSentIsHandedToTheNetworkJob() = runBlocking {
        // the job may start and finish at once (this phone has a network), so "it exists" is what proves it was handed over
        work.cancelUniqueWork(SyncScheduler.WORK_SOON).result.get()
        work.pruneWork().result.get()
        signedInAndReady()
        SyncScheduler(context, scope, repo, sync).startPushOnChange { true }
        cloud.offline = true
        repo.add("written offline")
        assertTrue("the system will send it when the network is back", eventually { states(SyncScheduler.WORK_SOON).isNotEmpty() })
        assertEquals(1, repo.dirtyCountNow())
    }

    @Test fun withBackgroundSyncOffNothingIsHandedToTheSystem() = runBlocking {
        work.cancelUniqueWork(SyncScheduler.WORK_SOON).result.get()
        work.pruneWork().result.get()
        signedInAndReady()
        SyncScheduler(context, scope, repo, sync).startPushOnChange { false }
        cloud.offline = true
        repo.add("written offline")
        delay(3_500)
        assertTrue(states(SyncScheduler.WORK_SOON).isEmpty())
    }

    // ---- WorkManager jobs ----

    @Test fun theBackgroundJobNeedsANetworkAndRunsEveryQuarterHour() {
        SyncScheduler.scheduleBackground(context)
        val info = work.getWorkInfosForUniqueWork(SyncScheduler.WORK_PERIODIC).get().single()
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(15L * 60_000, info.periodicityInfo!!.repeatIntervalMillis)
        SyncScheduler.cancelBackground(context)
        work.pruneWork().result.get()
        assertTrue(states(SyncScheduler.WORK_PERIODIC).none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING })
    }

    @Test fun schedulingAgainKeepsTheSameJob() {
        SyncScheduler.scheduleBackground(context)
        val first = work.getWorkInfosForUniqueWork(SyncScheduler.WORK_PERIODIC).get().single().id
        SyncScheduler.scheduleBackground(context)                 // what every app start does
        SyncScheduler.scheduleBackground(context)
        assertEquals(first, work.getWorkInfosForUniqueWork(SyncScheduler.WORK_PERIODIC).get().single().id)
    }

    @Test fun theNetworkJobIsForUnsentChangesOnly() {
        SyncScheduler.syncWhenOnline(context)
        val info = work.getWorkInfosForUniqueWork(SyncScheduler.WORK_SOON).get().first()
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        work.cancelUniqueWork(SyncScheduler.WORK_SOON)
    }

    @Test fun theWorkerDoesNothingWhenSignedOut() = runTest {
        // the real app of this phone; skipped when somebody is signed in (it would really sync)
        val app = context as com.chaosnoteviewer.ChaosApp
        app.awaitSignIn()
        org.junit.Assume.assumeFalse(app.account.state.value.account is com.chaosnoteviewer.account.Account.SignedIn)
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.ONLY_IF_DIRTY to true)).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
    }
}
