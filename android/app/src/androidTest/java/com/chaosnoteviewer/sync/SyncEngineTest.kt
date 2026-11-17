package com.chaosnoteviewer.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaosnoteviewer.crypto.NoteCrypto
import com.chaosnoteviewer.data.AppDatabase
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NotesRepository
import com.chaosnoteviewer.data.StateKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.fail
import org.junit.runner.RunWith

// Two phones (two databases) and one cloud. The clock of every phone is its own and can be moved.
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val uid = "user-1"
    private val key = ByteArray(32) { it.toByte() }
    private val cloud = FakeRemoteStore()

    private class Phone(val db: AppDatabase, val cloud: FakeRemoteStore) {
        var clock = 1_000L
        val repo = NotesRepository(db) { clock }
        val engine = SyncEngine(repo, cloud)
        suspend fun notes() = repo.alive.first()
        suspend fun titles() = notes().map { it.title }.sorted()
        suspend fun byTitle(t: String) = notes().first { it.title == t }
        suspend fun sync(key: ByteArray, uid: String) = engine.run(uid, key)
    }

    private val phones = mutableListOf<Phone>()
    private fun phone(): Phone {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        return Phone(db, cloud).also { phones += it }
    }

    @Before fun reset() { phones.clear() }
    @After fun close() { phones.forEach { it.db.close() } }

    private suspend fun Phone.sync() = sync(key, uid)

    @Test fun pushUploadsEncryptedAndMarksClean() = runTest {
        val a = phone()
        val n = a.repo.add("Buy milk", "2 litres", dueDate = 86_400_000L)
        val r = a.sync()
        assertEquals(1, r.pushed)
        val stored = cloud.content(uid, n.id)!!
        assertTrue(stored.startsWith("v1."))
        assertFalse("the cloud must not see the text", stored.contains("milk"))
        // what is in the cloud is exactly what desktop would write: decrypts with the id as authenticated data
        val json = NoteCrypto.decrypt(key, stored, n.id)!!
        assertEquals("Buy milk", NoteJson.parse(json)!!.title)
        val row = a.repo.get(n.id)!!
        assertFalse(row.dirty)
        assertNotNull(row.remoteUpdatedAt)
    }

    @Test fun anIdleSyncSendsNothing() = runTest {
        val a = phone()
        a.repo.add("x")
        a.sync()
        val before = cloud.uploads
        val r = a.sync()
        assertEquals(0, r.pushed)
        assertEquals(0, r.pulled)                 // our own push coming back changes nothing
        assertEquals(before, cloud.uploads)
    }

    @Test fun aNewPhoneGetsTheNotes() = runTest {
        val a = phone()
        a.repo.add("one", "d1", dueDate = 5 * 86_400_000L); a.repo.add("two"); a.sync()
        val b = phone()
        val r = b.sync()
        assertEquals(2, r.pulled)
        assertEquals(listOf("one", "two"), b.titles())
        val one = b.byTitle("one")
        assertEquals("d1", one.description)
        assertEquals(5 * 86_400_000L, one.dueDate)
        assertEquals(uid, one.authorId)
        assertFalse(one.dirty)
        assertNotNull(one.remoteUpdatedAt)
    }

    @Test fun anEditTravelsToTheOtherPhone() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("draft"); a.sync(); b.sync()
        a.clock = 5_000L
        a.repo.update(n.id, "final", "text", null); a.repo.setCompleted(n.id, true); a.sync()
        b.sync()
        val got = b.repo.get(n.id)!!
        assertEquals("final", got.title)
        assertEquals("text", got.description)
        assertTrue(got.completed)
        assertFalse(got.dirty)
    }

    @Test fun aStarTravelsToTheOtherPhoneAndBack() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("important"); a.sync(); b.sync()
        assertFalse(b.repo.get(n.id)!!.starred)

        a.clock = 5_000L
        a.repo.setStarred(n.id, true); a.sync(); b.sync()
        assertTrue(b.repo.get(n.id)!!.starred)

        b.clock = 9_000L
        b.repo.setStarred(n.id, false); b.sync(); a.sync()
        assertFalse(a.repo.get(n.id)!!.starred)
    }

    // an edit of the text on this phone must not lose a star that came from another device
    @Test fun editingTheTextKeepsTheStar() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("title"); a.repo.setStarred(n.id, true); a.sync(); b.sync()
        b.clock = 5_000L
        b.repo.update(n.id, "new title", "", null); b.sync(); a.sync()
        val got = a.repo.get(n.id)!!
        assertEquals("new title", got.title)
        assertTrue(got.starred)
    }

    // a note written before stars existed (no "starred" in the content) arrives as not starred
    @Test fun aNoteFromAnOlderClientIsNotStarred() = runTest {
        val json = """{"title":"old","description":"","completed":false,"position":1,"dueDate":null,"createdAt":1,"updatedAt":2}"""
        cloud.put(uid, "old-note", NoteCrypto.encrypt(key, json, "old-note"))
        val a = phone(); a.sync()
        val got = a.repo.get("old-note")!!
        assertEquals("old", got.title)
        assertFalse(got.starred)
    }

    // The phone read the note before it knew stars (the star was dropped) and the cursor moved on; after the update
    // the pull comes again and the star, on the same version of the note, is taken over.
    @Test fun aStarOnTheSameVersionIsTakenOverOnTheNextPull() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("important"); a.sync(); b.sync()
        val local = b.repo.get(n.id)!!
        assertFalse(local.starred)
        cloud.put(uid, n.id, NoteCrypto.encrypt(key, NoteJson.encode(local.copy(starred = true)), n.id))   // same updatedAt
        b.repo.setState(StateKeys.CURSOR_TS, ""); b.repo.setState(StateKeys.CURSOR_ID, "")                  // what the migration does

        assertEquals(1, b.sync().pulled)
        val got = b.repo.get(n.id)!!
        assertTrue(got.starred)
        assertEquals("the time of the note stays", local.updatedAt, got.updatedAt)
        assertFalse(got.dirty)
    }

    @Test fun reorderingTravelsToo() = runTest {
        val a = phone(); val b = phone()
        a.repo.add("a"); a.repo.add("b"); a.repo.add("c"); a.sync(); b.sync()
        a.clock = 3_000L
        a.repo.move(a.notes(), from = 2, to = 0); a.sync(); b.sync()
        assertEquals(listOf("c", "a", "b"), b.notes().map { it.title })
    }

    @Test fun conflictTheLaterEditWinsOnBothPhones() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("start"); a.sync(); b.sync()
        a.clock = 10_000L; a.repo.update(n.id, "from A", "", null)
        b.clock = 20_000L; b.repo.update(n.id, "from B", "", null)         // later
        a.sync(); b.sync(); a.sync()
        assertEquals("from B", a.repo.get(n.id)!!.title)
        assertEquals("from B", b.repo.get(n.id)!!.title)
        assertFalse(a.repo.get(n.id)!!.dirty)
        assertFalse(b.repo.get(n.id)!!.dirty)
    }

    @Test fun anOlderEditNeverOverwritesANewerLocalOne() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("start"); a.sync(); b.sync()
        a.clock = 50_000L; a.repo.update(n.id, "newer, not pushed yet", "", null)
        b.clock = 9_000L; b.repo.update(n.id, "older", "", null); b.sync()
        a.sync()                                                           // push first: the cloud gets the newer one
        assertEquals("newer, not pushed yet", a.repo.get(n.id)!!.title)
        b.sync()
        assertEquals("newer, not pushed yet", b.repo.get(n.id)!!.title)
    }

    @Test fun deletingRemovesItEverywhereItWasSynced() = runTest {
        val a = phone()
        val n = a.repo.add("gone soon"); a.sync()
        a.clock = 4_000L; a.repo.delete(n.id)
        assertNotNull(a.repo.get(n.id))                  // hidden, waiting for the server to confirm
        val r = a.sync()
        assertEquals(1, r.pushed)
        assertNull(cloud.content(uid, n.id))             // really deleted in the cloud
        assertNull(a.repo.get(n.id))                     // and now for good here
    }

    @Test fun aNoteNeverUploadedIsJustRemoved() = runTest {
        val a = phone()
        val n = a.repo.add("local only"); a.repo.delete(n.id)
        assertEquals(0, a.sync().pushed)
        assertEquals(0, cloud.uploads)
        assertNull(a.repo.get(n.id))
    }

    @Test fun otherPhonesKeepANoteDeletedElsewhereAndFlagIt() = runTest {
        val a = phone(); val b = phone()
        val keep = a.repo.add("keep"); val del = a.repo.add("delete me"); a.sync(); b.sync()
        a.clock = 6_000L; a.repo.delete(del.id); a.sync()
        b.sync()
        // NOT deleted on B, as on desktop: the user decides
        assertEquals(listOf("delete me", "keep"), b.titles())
        assertEquals(listOf(del.id), b.engine.reconcile(uid))
        assertEquals(emptyList<String>(), a.engine.reconcile(uid))
        assertNotNull(a.repo.get(keep.id))
    }

    @Test fun reconcileIsOneCountQueryWhenNothingVanished() = runTest {
        val a = phone(); a.repo.add("x"); a.repo.add("y"); a.sync()
        cloud.listIdsCalls = 0
        assertEquals(emptyList<String>(), a.engine.reconcile(uid))
        assertEquals(0, cloud.listIdsCalls)
    }

    @Test fun uploadAgainBringsADeletedNoteBack() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("precious"); a.sync(); b.sync()
        a.clock = 6_000L; a.repo.delete(n.id); a.sync()
        assertEquals(listOf(n.id), b.engine.reconcile(uid))
        b.repo.reupload(n.id)                              // "keep it"
        b.sync()
        assertEquals("precious", NoteJson.parse(NoteCrypto.decrypt(key, cloud.content(uid, n.id)!!, n.id)!!)!!.title)
        assertEquals(emptyList<String>(), b.engine.reconcile(uid))
        a.sync()
        assertEquals(listOf("precious"), a.titles())       // and it reaches the phone that deleted it
    }

    @Test fun deletingANoteThatIsAlreadyGoneInTheCloudWorks() = runTest {
        val a = phone(); val b = phone()
        val n = a.repo.add("x"); a.sync(); b.sync()
        a.repo.delete(n.id); a.sync()
        b.clock = 9_000L; b.repo.delete(n.id)               // "delete here" on the flagged note
        assertEquals(1, b.sync().pushed)
        assertNull(b.repo.get(n.id))
    }

    @Test fun aWrongKeyChangesNothing() = runTest {
        val a = phone(); a.repo.add("secret"); a.sync()
        val b = phone()
        try {
            b.sync(ByteArray(32) { 99 }, uid)
            fail("must not read the notes with another key")
        } catch (e: SyncException) {
            assertEquals("BAD_KEY", e.code)
        }
        assertEquals(emptyList<String>(), b.titles())
        assertEquals("", b.repo.getState(StateKeys.CURSOR_TS))       // the cursor did not move
    }

    @Test fun unreadableDocumentsAreSkippedButTheCursorMovesOn() = runTest {
        cloud.put(uid, "stranger-1", "just some text")
        cloud.put(uid, "stranger-2", NoteCrypto.encrypt(key, "{not a note}", "stranger-2"))   // ours, but not a note
        val a = phone(); val n = a.repo.add("mine"); a.sync()
        val b = phone()
        b.sync()
        assertEquals(listOf("mine"), b.titles())
        assertEquals(0, b.sync().pulled)
        assertFalse(b.repo.getState(StateKeys.CURSOR_ID).isEmpty())
        assertNotNull(b.repo.get(n.id))
    }

    @Test fun aLongPullGoesThroughAllPagesWithoutDuplicates() = runTest {
        val a = phone()
        repeat(450) { a.repo.add("note $it") }
        a.sync()
        val b = phone()
        val r = b.sync()
        assertEquals(450, r.pulled)
        assertEquals(450, b.notes().size)
        assertEquals(450, b.notes().map { it.id }.toSet().size)
        assertEquals(0, b.sync().pulled)
    }

    @Test fun offlineKeepsEverythingDirtyAndTheNextSyncSendsIt() = runTest {
        val a = phone()
        val n = a.repo.add("written offline")
        cloud.offline = true
        try { a.sync(); fail() } catch (e: SyncException) { assertEquals("NETWORK", e.code) }
        assertTrue(a.repo.get(n.id)!!.dirty)
        cloud.offline = false
        assertEquals(1, a.sync().pushed)
        assertFalse(a.repo.get(n.id)!!.dirty)
    }

    @Test fun aRefusedUploadLeavesTheNoteDirty() = runTest {
        val a = phone()
        val n = a.repo.add("x")
        cloud.failNextUpload = SyncException("PERMISSION_DENIED", "no")
        try { a.sync(); fail() } catch (e: SyncException) { assertEquals("PERMISSION_DENIED", e.code) }
        assertTrue(a.repo.get(n.id)!!.dirty)
        assertEquals(1, a.sync().pushed)
    }

    @Test fun aNoteEditedWhileItWasBeingPushedStaysDirty() = runTest {
        val a = phone()
        val n = a.repo.add("v1")
        val sent = a.repo.get(n.id)!!                       // the version the push started with
        a.clock = 7_000L; a.repo.update(n.id, "v2", "", null)
        a.repo.markPushed(sent.id, sent.updatedAt, 123)     // the push of the old version finishes now
        val row = a.repo.get(n.id)!!
        assertTrue("the newer edit still has to go up", row.dirty)
        assertEquals("v2", row.title)
    }

    @Test fun twoNotesWrittenOnTwoPhonesBothSurvive() = runTest {
        val a = phone(); val b = phone()
        a.repo.add("from A"); b.repo.add("from B")
        a.sync(); b.sync(); a.sync()
        assertEquals(listOf("from A", "from B"), a.titles())
        assertEquals(listOf("from A", "from B"), b.titles())
    }

    @Test fun afterAKeyResetEverythingGoesUpAgain() = runTest {
        val a = phone()
        a.repo.add("one"); val two = a.repo.add("two"); a.sync()
        a.repo.delete(two.id)                                // a pending delete is moot after a wipe
        cloud.wipe(uid)
        a.repo.forgetRemote()
        assertEquals("", a.repo.getState(StateKeys.CURSOR_TS))
        val r = a.sync(ByteArray(32) { 7 }, uid)
        assertEquals(1, r.pushed)
        assertEquals(setOf(a.byTitle("one").id), cloud.docIds(uid))
    }
}
