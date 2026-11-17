package com.chaosnoteviewer.ui

import com.chaosnoteviewer.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentChangesTest {
    private fun note(id: String, completed: Boolean = false) =
        NoteEntity(id = id, title = "Note $id", position = 1.0, completed = completed, createdAt = 0, updatedAt = 0)

    private fun ids(list: List<NoteEntity>) = list.map { it.id }

    @Test
    fun noRecentChangesLeavesTheListAlone() {
        val shown = listOf(note("a"), note("b"))
        assertEquals(shown, RecentChanges.apply(shown, emptyMap()))
    }

    @Test
    fun aNoteTheFilterHidesComesBackAtItsOldPlace() {
        // "b" was the second row of the Open list, was completed by a swipe and dropped out of the list
        val swiped = note("b")
        val recent = mapOf("b" to RecentChange(swiped, toCompleted = true, index = 1))
        val result = RecentChanges.apply(listOf(note("a"), note("c")), recent)
        assertEquals(listOf("a", "b", "c"), ids(result))
        assertTrue("the ghost shows the state after the swipe", result[1].completed)
    }

    @Test
    fun aNoteThatIsStillInTheListIsNotAddedTwice() {
        // in the All list a completed note stays where it is
        val recent = mapOf("b" to RecentChange(note("b"), toCompleted = true, index = 1))
        val shown = listOf(note("a"), note("b", completed = true), note("c"))
        assertEquals(ids(shown), ids(RecentChanges.apply(shown, recent)))
    }

    @Test
    fun severalGhostsGoBackToTheirOwnPlaces() {
        // rows were a, b, c, d, e; "b" and "d" were swiped away
        val recent = mapOf(
            "d" to RecentChange(note("d"), true, index = 3),
            "b" to RecentChange(note("b"), true, index = 1),
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(RecentChanges.apply(listOf(note("a"), note("c"), note("e")), recent)))
    }

    @Test
    fun anIndexPastTheEndIsClamped() {
        val recent = mapOf("z" to RecentChange(note("z"), true, index = 9))
        assertEquals(listOf("a", "z"), ids(RecentChanges.apply(listOf(note("a")), recent)))
    }

    @Test
    fun reopeningInTheCompletedListIsAGhostToo() {
        val recent = mapOf("b" to RecentChange(note("b", completed = true), toCompleted = false, index = 0))
        val result = RecentChanges.apply(listOf(note("x", completed = true)), recent)
        assertEquals(listOf("b", "x"), ids(result))
        assertEquals(false, result[0].completed)
        assertEquals(mapOf("b" to false), RecentChanges.ghosts(recent))
    }
}
