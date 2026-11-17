package com.chaosnoteviewer.extras

import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteQuery.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class ReminderTextTest {
    private val today = 20_000 * DAY_MS            // a UTC midnight
    private val utc = ZoneOffset.UTC

    private fun note(title: String, due: Long?, position: Double = 1.0, description: String = "") =
        NoteEntity(id = title, title = title, description = description, position = position, dueDate = due, createdAt = 0, updatedAt = 0)

    @Test fun aNoteDueTodayIsDueToday() {
        assertEquals("Due today", ReminderText.deadline(note("a", today), today, Locale.ENGLISH, utc))
    }

    @Test fun anOverdueNoteSaysSinceWhen() {
        // 20 000 days after 1970-01-01 is 2024-10-04
        assertEquals("Overdue since Oct 2", ReminderText.deadline(note("a", today - 2 * DAY_MS), today, Locale.ENGLISH, utc))
    }

    @Test fun noDeadlineGivesNoLine() {
        assertEquals("", ReminderText.deadline(note("a", null), today, Locale.ENGLISH, utc))
    }

    @Test fun theFirstLineSkipsBlankOnes() {
        assertEquals("Book an appointment", ReminderText.firstLine("\n  \nBook an appointment  \nsecond"))
        assertEquals("", ReminderText.firstLine("   \n "))
        assertEquals("", ReminderText.firstLine(""))
    }

    @Test fun theNotificationIdIsStableAndKeepsClearOfTheSmallIds() {
        val id = ReminderText.notificationId("3f1b0f0e-aaaa-4bbb-8ccc-123456789abc")
        assertEquals(id, ReminderText.notificationId("3f1b0f0e-aaaa-4bbb-8ccc-123456789abc"))
        assertTrue("positive and above the reserved ids: $id", id >= 1_000)
        assertNotEquals(id, ReminderText.notificationId("another-note"))
        // even the worst hash stays positive
        assertTrue(ReminderText.notificationId("\u0000".repeat(40)) >= 1_000)
    }

    @Test fun todayComesFirstThenTheMostRecentlyMissed() {
        val s = DeadlineSummary.of(listOf(
            note("old", today - 5 * DAY_MS), note("now", today, position = 2.0), note("recent", today - DAY_MS), note("now2", today, position = 1.0),
        ), today)
        val (shown, more) = s.forNotifications()
        assertEquals(listOf("now2", "now", "recent", "old"), shown.map { it.title })
        assertEquals(0, more)
    }

    @Test fun onlyTheFirstFewGetANotificationOfTheirOwn() {
        val s = DeadlineSummary.of((1..12).map { note("n$it", today - it * DAY_MS, position = it.toDouble()) }, today)
        val (shown, more) = s.forNotifications(limit = 8)
        assertEquals(8, shown.size)
        assertEquals(4, more)
        assertEquals("n1", shown.first().title)     // the most recently missed
    }
}
