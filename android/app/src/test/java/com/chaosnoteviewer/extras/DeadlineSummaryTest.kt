package com.chaosnoteviewer.extras

import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteQuery.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineSummaryTest {
    private val today = 100 * DAY_MS

    private fun note(title: String, due: Long?, completed: Boolean = false, position: Double = 1.0, deleted: Boolean = false) =
        NoteEntity(id = title, title = title, completed = completed, position = position, dueDate = due,
            createdAt = 0, updatedAt = 0, deletedAt = if (deleted) 1 else null)

    private fun titles(l: List<NoteEntity>) = l.map { it.title }

    @Test fun overdueAndTodayAreSeparated() {
        val s = DeadlineSummary.of(listOf(
            note("yesterday", today - DAY_MS), note("today", today), note("tomorrow", today + DAY_MS), note("no deadline", null),
        ), today)
        assertEquals(listOf("yesterday"), titles(s.overdue))
        assertEquals(listOf("today"), titles(s.today))
        assertEquals(2, s.total)
    }

    @Test fun completedAndDeletedNotesAreIgnored() {
        val s = DeadlineSummary.of(listOf(
            note("done", today, completed = true), note("gone", today - DAY_MS, deleted = true), note("open", today),
        ), today)
        assertEquals(listOf("open"), titles(s.today))
        assertTrue(s.overdue.isEmpty())
    }

    @Test fun theOldestDeadlineComesFirstThenYourOrder() {
        val s = DeadlineSummary.of(listOf(
            note("b", today - DAY_MS, position = 2.0), note("a", today - 3 * DAY_MS, position = 5.0), note("c", today - DAY_MS, position = 1.0),
        ), today)
        assertEquals(listOf("a", "c", "b"), titles(s.overdue))
    }

    @Test fun nothingDueIsEmpty() {
        assertTrue(DeadlineSummary.of(listOf(note("later", today + 5 * DAY_MS), note("x", null)), today).isEmpty)
        assertTrue(DeadlineSummary.of(emptyList(), today).isEmpty)
    }
}
