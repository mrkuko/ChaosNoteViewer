package com.chaosnoteviewer.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaosnoteviewer.crypto.FakeWrapper
import com.chaosnoteviewer.crypto.KeyStorage
import com.chaosnoteviewer.crypto.KeyText
import com.chaosnoteviewer.crypto.NoteCrypto
import com.chaosnoteviewer.data.AppDatabase
import com.chaosnoteviewer.data.NotesRepository
import com.chaosnoteviewer.data.StateKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// What the user sees: the state of the key flow and the sync of one phone, on top of the real database.
@RunWith(AndroidJUnit4::class)
class SyncManagerTest {
    private val uid = "user-1"
    private val cloud = FakeRemoteStore()
    private val dbs = mutableListOf<AppDatabase>()
    private var clock = 1_000L

    private inner class Phone {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
            .also { dbs += it }
        val repo = NotesRepository(db) { clock }
        val storage = KeyStorage(repo, FakeWrapper())
        val manager = SyncManager(repo, cloud, storage) { clock }
        val state get() = manager.state.value
        suspend fun signIn() { repo.setState(StateKeys.UID, uid); manager.onSignedIn(true) }
        suspend fun titles() = repo.alive.first().map { it.title }.sorted()
    }

    @After fun close() = dbs.forEach { it.close() }

    @Test fun beforeSigningInNothingHappens() = runTest {
        val a = Phone()
        a.manager.syncNow()
        assertFalse(a.state.signedIn)
        assertEquals(0, cloud.uploads)
    }

    @Test fun firstPhoneMustSaveTheKeyBeforeAnythingIsUploaded() = runTest {
        val a = Phone()
        a.repo.add("note")
        a.signIn()
        assertEquals(KeyPhase.Save, a.state.phase)
        assertEquals(4, a.state.keyBlock.lines().size)
        a.manager.syncNow()
        assertEquals("sync waits for the confirmation", 0, cloud.uploads)

        clock = 9_000L
        a.manager.confirmKey()
        assertEquals(KeyPhase.Ready, a.state.phase)
        assertEquals("", a.state.keyBlock)                  // hidden again
        assertEquals(1, cloud.docIds(uid).size)
        assertEquals(9_000L, a.state.lastSyncMs)
        assertEquals("", a.state.syncError)
    }

    @Test fun secondPhoneTypesTheKeyAndGetsTheNotes() = runTest {
        val a = Phone(); a.repo.add("from A"); a.signIn(); a.manager.confirmKey()
        val shown = a.state.keyBlock.ifEmpty { KeyText.formatBlock(a.storage.load()!!) }

        val b = Phone(); b.signIn()
        assertEquals(KeyPhase.Enter, b.state.phase)
        assertFalse(b.state.mismatch)
        b.manager.submitKey("WRONG")
        assertEquals(KeyPhase.Enter, b.state.phase)
        assertTrue(b.state.keyError.isNotEmpty())
        b.manager.submitKey(shown)
        assertEquals(KeyPhase.Ready, b.state.phase)
        assertEquals("", b.state.keyError)
        assertEquals(listOf("from A"), b.titles())
    }

    @Test fun aKeyResetElsewhereIsNoticedBySyncAndAsksForTheNewKey() = runTest {
        val a = Phone(); a.signIn(); a.manager.confirmKey()
        val b = Phone(); b.signIn(); b.manager.submitKey(KeyText.formatBlock(a.storage.load()!!))
        assertEquals(KeyPhase.Ready, b.state.phase)
        a.repo.add("after reset")

        a.manager.resetKey()
        assertEquals(KeyPhase.Save, a.state.phase)
        a.manager.confirmKey()

        b.manager.syncNow()
        assertEquals(KeyPhase.Enter, b.state.phase)
        assertTrue(b.state.mismatch)
        b.manager.submitKey(KeyText.formatBlock(a.storage.load()!!))
        assertEquals(KeyPhase.Ready, b.state.phase)
        assertEquals(listOf("after reset"), b.titles())
    }

    @Test fun offlineShowsTheErrorAndRecovers() = runTest {
        val a = Phone(); a.signIn(); a.manager.confirmKey()
        a.repo.add("offline note")
        cloud.offline = true
        a.manager.syncNow()
        assertEquals("Could not reach Firestore. (NETWORK)", a.state.syncError)
        assertFalse(a.state.syncing)
        cloud.offline = false
        clock = 20_000L
        a.manager.syncNow()
        assertEquals("", a.state.syncError)
        assertEquals(20_000L, a.state.lastSyncMs)
        assertEquals(1, cloud.docIds(uid).size)
    }

    @Test fun startingOfflineWithAConfirmedKeyStaysReady() = runTest {
        val a = Phone(); a.signIn(); a.manager.confirmKey()
        cloud.offline = true
        a.manager.startKeyFlow()
        assertEquals(KeyPhase.Ready, a.state.phase)
        assertTrue(a.state.keyError.contains("NETWORK"))
    }

    @Test fun anAnnoyedPhoneFlagsNotesDeletedElsewhereAndCanUploadThemAgain() = runTest {
        val a = Phone(); val n = a.repo.add("shared"); a.signIn(); a.manager.confirmKey()
        val b = Phone(); b.signIn(); b.manager.submitKey(KeyText.formatBlock(a.storage.load()!!))
        assertEquals(listOf("shared"), b.titles())

        a.repo.delete(n.id); a.manager.syncNow()
        b.manager.syncNow()
        assertEquals("not deleted here", listOf("shared"), b.titles())
        assertEquals(setOf(n.id), b.state.missing)

        b.manager.reuploadNote(n.id)
        assertEquals(emptySet<String>(), b.state.missing)
        assertEquals(setOf(n.id), cloud.docIds(uid))
        a.manager.syncNow()
        assertEquals(listOf("shared"), a.titles())
    }

    @Test fun signingOutClearsTheState() = runTest {
        val a = Phone(); a.signIn(); a.manager.confirmKey()
        a.manager.onSignedIn(false)
        assertEquals(SyncState(), a.state)
    }

    @Test fun theKeyCanBeShownAndHiddenWhileReady() = runTest {
        val a = Phone(); a.signIn(); a.manager.confirmKey()
        a.manager.toggleKeyShown()
        assertEquals(KeyText.formatBlock(a.storage.load()!!), a.state.keyBlock)
        a.manager.toggleKeyShown()
        assertEquals("", a.state.keyBlock)
    }

    @Test fun whatTheCloudStoresIsEncrypted() = runTest {
        val a = Phone(); a.repo.add("very private text"); a.signIn(); a.manager.confirmKey()
        val id = cloud.docIds(uid).single()
        assertFalse(cloud.content(uid, id)!!.contains("private"))
        assertFalse(cloud.checks[uid]!!.contains(KeyText.format(a.storage.load()!!)))
        assertEquals("very private text", NoteJson.parse(NoteCrypto.decrypt(a.storage.load()!!, cloud.content(uid, id)!!, id)!!)!!.title)
    }
}
