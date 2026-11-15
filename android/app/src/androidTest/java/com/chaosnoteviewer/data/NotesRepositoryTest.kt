package com.chaosnoteviewer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: NotesRepository
    private var clock = 1_000L

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = NotesRepository(db) { clock }
    }

    @After fun close() = db.close()

    private suspend fun alive() = repo.alive.first()

    @Test fun addSetsIdTimesAndDirty() = runTest {
        val n = repo.add("First", "text", dueDate = 86_400_000L)
        val row = repo.get(n.id)!!
        assertEquals(4, row.id.split("-")[2].length)
        assertEquals('4', row.id.split("-")[2][0])          // UUID version 4
        assertEquals("First", row.title)
        assertEquals("text", row.description)
        assertEquals(86_400_000L, row.dueDate)
        assertEquals(1_000L, row.createdAt)
        assertEquals(1_000L, row.updatedAt)
        assertTrue(row.dirty)
        assertFalse(row.completed)
        assertNull(row.authorId)
        assertNull(row.deletedAt)
        assertNull(row.remoteUpdatedAt)
    }

    @Test fun newNotesGoToTheEndOfTheQueue() = runTest {
        repo.add("a"); repo.add("b"); repo.add("c")
        assertEquals(listOf("a", "b", "c"), alive().map { it.title })
        assertEquals(listOf(1.0, 2.0, 3.0), alive().map { it.position })
    }

    @Test fun updateBumpsUpdatedAtAndDirty() = runTest {
        val n = repo.add("a")
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET dirty = 0")
        clock = 2_000L
        repo.update(n.id, "b", "desc", null)
        val row = repo.get(n.id)!!
        assertEquals("b", row.title)
        assertEquals("desc", row.description)
        assertEquals(1_000L, row.createdAt)
        assertEquals(2_000L, row.updatedAt)
        assertTrue(row.dirty)
    }

    @Test fun setCompletedBumpsUpdatedAt() = runTest {
        val n = repo.add("a")
        clock = 3_000L
        repo.setCompleted(n.id, true)
        assertTrue(repo.get(n.id)!!.completed)
        assertEquals(3_000L, repo.get(n.id)!!.updatedAt)
    }

    @Test fun deleteOfUnsyncedNoteRemovesTheRow() = runTest {
        val n = repo.add("a")
        repo.delete(n.id)
        assertNull(repo.get(n.id))
    }

    @Test fun deleteOfSyncedNoteIsSoft() = runTest {
        val n = repo.add("a")
        repo.add("b")
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET dirty = 0, remote_updated_at = 500 WHERE id = '${n.id}'")
        clock = 4_000L
        repo.delete(n.id)
        val row = repo.get(n.id)
        assertNotNull(row)
        assertEquals(4_000L, row!!.deletedAt)
        assertEquals(4_000L, row.updatedAt)
        assertTrue(row.dirty)
        assertEquals(listOf("b"), alive().map { it.title })      // hidden from the list
    }

    @Test fun deleteCompletedOnlyDeletesCompleted() = runTest {
        val a = repo.add("a"); repo.add("b"); val c = repo.add("c")
        repo.setCompleted(a.id, true); repo.setCompleted(c.id, true)
        repo.deleteCompleted()
        assertEquals(listOf("b"), alive().map { it.title })
    }

    @Test fun moveBetweenTwoNotesWritesOnlyTheMovedOne() = runTest {
        repo.add("a"); repo.add("b"); repo.add("c")
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET dirty = 0")
        clock = 5_000L
        repo.move(alive(), from = 2, to = 1)                    // c between a and b
        val list = alive()
        assertEquals(listOf("a", "c", "b"), list.map { it.title })
        assertEquals(1.5, list[1].position, 0.0)
        assertEquals(listOf(false, true, false), list.map { it.dirty })
    }

    @Test fun moveToTopAndBottom() = runTest {
        repo.add("a"); repo.add("b"); repo.add("c")
        repo.move(alive(), from = 2, to = 0)
        assertEquals(listOf("c", "a", "b"), alive().map { it.title })
        repo.move(alive(), from = 0, to = 2)
        assertEquals(listOf("a", "b", "c"), alive().map { it.title })
    }

    @Test fun moveRenumbersWhenNeighboursAreTooClose() = runTest {
        repo.add("a"); repo.add("b"); repo.add("c")
        val a = alive()[0]
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET position = 1.0000000000001 WHERE title = 'b'")
        repo.move(alive(), from = 2, to = 1)                    // c between a and b, too close
        assertEquals(listOf("a", "c", "b"), alive().map { it.title })
        assertEquals(1.0, repo.get(a.id)!!.position, 0.0)
    }

    @Test fun claimNotesGivesUnownedNotesToTheAccountAndQueuesThem() = runTest {
        val a = repo.add("a"); val b = repo.add("b")
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET dirty = 0, author_id = 'other' WHERE id = '${b.id}'")
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET dirty = 0 WHERE id = '${a.id}'")
        clock = 6_000L
        repo.claimNotes("uid-1")
        assertEquals("uid-1", repo.get(a.id)!!.authorId)
        assertTrue(repo.get(a.id)!!.dirty)
        assertEquals(6_000L, repo.get(a.id)!!.updatedAt)
        assertEquals("other", repo.get(b.id)!!.authorId)         // somebody else's note is left alone
        assertFalse(repo.get(b.id)!!.dirty)
    }

    @Test fun rebindNotesMovesNotesToTheNewUid() = runTest {
        val a = repo.add("a"); val b = repo.add("b")
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET author_id = 'old' WHERE id = '${a.id}'")
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET author_id = 'other', dirty = 0 WHERE id = '${b.id}'")
        repo.rebindNotes("old", "new")
        assertEquals("new", repo.get(a.id)!!.authorId)
        assertTrue(repo.get(a.id)!!.dirty)
        assertEquals("other", repo.get(b.id)!!.authorId)
    }

    @Test fun stateKeyValue() = runTest {
        assertEquals("", repo.getState(StateKeys.UID))
        repo.setState(StateKeys.UID, "u1")
        repo.setState(StateKeys.UID, "u2")
        assertEquals("u2", repo.getState(StateKeys.UID))
        repo.clearState(StateKeys.UID)
        assertEquals("", repo.getState(StateKeys.UID))
    }
}
