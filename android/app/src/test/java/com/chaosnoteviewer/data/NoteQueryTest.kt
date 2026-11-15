package com.chaosnoteviewer.data

import com.chaosnoteviewer.data.NoteQuery.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

class NoteQueryTest {
    private val today = 100 * DAY_MS
    private val collator = Collator.getInstance(Locale.ROOT)

    private fun note(
        title: String, position: Double, completed: Boolean = false, due: Long? = null, created: Long = 0,
    ) = NoteEntity(id = title, title = title, completed = completed, position = position, dueDate = due,
        createdAt = created, updatedAt = created)

    private fun titles(list: List<NoteEntity>) = list.map { it.title }

    private fun shown(notes: List<NoteEntity>, f: NoteFilter, s: SortField = SortField.OWN, desc: Boolean = false) =
        titles(NoteQuery.shown(notes, f, s, desc, today, collator))

    private val mixed = listOf(
        note("open", 1.0),
        note("done", 2.0, completed = true),
        note("overdue", 3.0, due = today - DAY_MS),
        note("today", 4.0, due = today),
        note("in2days", 5.0, due = today + 2 * DAY_MS),
        note("in3days", 6.0, due = today + 3 * DAY_MS),
        note("doneOverdue", 7.0, completed = true, due = today - DAY_MS),
    )

    @Test fun openFilter() =
        assertEquals(listOf("open", "overdue", "today", "in2days", "in3days"), shown(mixed, NoteFilter.OPEN))

    @Test fun completedFilter() = assertEquals(listOf("done", "doneOverdue"), shown(mixed, NoteFilter.COMPLETED))

    @Test fun allFilter() = assertEquals(7, shown(mixed, NoteFilter.ALL).size)

    // overdue, today, tomorrow and the day after; the 3rd day from today is out
    @Test fun importantIsOpenAndDueWithinThreeDays() =
        assertEquals(listOf("overdue", "today", "in2days"), shown(mixed, NoteFilter.IMPORTANT))

    @Test fun overdue() {
        assertTrue(NoteQuery.isOverdue(note("a", 1.0, due = today - 1), today))
        assertFalse(NoteQuery.isOverdue(note("a", 1.0, due = today), today))
        assertFalse(NoteQuery.isOverdue(note("a", 1.0, completed = true, due = today - DAY_MS), today))
        assertFalse(NoteQuery.isOverdue(note("a", 1.0), today))
    }

    @Test fun ownOrderIsPosition() {
        val notes = listOf(note("c", 1.0), note("a", 2.0), note("b", 3.0))
        assertEquals(listOf("c", "a", "b"), shown(notes, NoteFilter.ALL))
    }

    @Test fun nameBothDirections() {
        val notes = listOf(note("b", 1.0), note("C", 2.0), note("a", 3.0))
        assertEquals(listOf("a", "b", "C"), shown(notes, NoteFilter.ALL, SortField.NAME))
        assertEquals(listOf("C", "b", "a"), shown(notes, NoteFilter.ALL, SortField.NAME, desc = true))
    }

    @Test fun deadlineWithoutDateGoesLastInBothDirections() {
        val notes = listOf(note("none", 1.0), note("late", 2.0, due = today + 5 * DAY_MS), note("soon", 3.0, due = today))
        assertEquals(listOf("soon", "late", "none"), shown(notes, NoteFilter.ALL, SortField.DEADLINE))
        assertEquals(listOf("late", "soon", "none"), shown(notes, NoteFilter.ALL, SortField.DEADLINE, desc = true))
    }

    @Test fun createdBothDirections() {
        val notes = listOf(note("mid", 1.0, created = 2), note("new", 2.0, created = 3), note("old", 3.0, created = 1))
        assertEquals(listOf("old", "mid", "new"), shown(notes, NoteFilter.ALL, SortField.CREATED))
        assertEquals(listOf("new", "mid", "old"), shown(notes, NoteFilter.ALL, SortField.CREATED, desc = true))
    }

    @Test fun tiesKeepOwnOrder() {
        val notes = listOf(note("x2", 2.0, due = today), note("x1", 1.0, due = today))
        assertEquals(listOf("x1", "x2"), shown(notes.sortedBy { it.position }, NoteFilter.ALL, SortField.DEADLINE, desc = true))
    }

    @Test fun naturalDirections() {
        assertFalse(SortField.NAME.defaultDesc)
        assertFalse(SortField.DEADLINE.defaultDesc)
        assertTrue(SortField.CREATED.defaultDesc)
    }
}
