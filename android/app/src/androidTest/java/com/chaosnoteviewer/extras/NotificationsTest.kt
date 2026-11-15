package com.chaosnoteviewer.extras

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.chaosnoteviewer.crypto.MemoryState
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteQuery
import com.chaosnoteviewer.data.NoteQuery.DAY_MS
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The real notifications of this phone: what is posted, with which text, and how they follow the notes.
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val today = NoteQuery.startOfToday()

    private fun note(title: String, due: Long? = null, completed: Boolean = false, position: Double = 1.0) =
        NoteEntity(id = title, title = title, completed = completed, position = position, dueDate = due, createdAt = 0, updatedAt = 0)

    // The system posts and cancels notifications a moment after the call: wait until it is as expected (or give up).
    private fun shown(id: Int, expected: Boolean = true): StatusBarNotification? {
        repeat(30) {
            val n = manager.activeNotifications.firstOrNull { it.id == id }
            if ((n != null) == expected) return n
            Thread.sleep(100)
        }
        return manager.activeNotifications.firstOrNull { it.id == id }
    }
    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun StatusBarNotification.lines() =
        notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()

    @Before @After fun clear() {
        Notifications.cancelDeadlines(context)
        Notifications.cancelCount(context)
    }

    @Test fun deadlineReminderListsOverdueAndTodayNotes() {
        val summary = DeadlineSummary.of(listOf(note("late", today - 2 * DAY_MS), note("now", today), note("later", today + DAY_MS)), today)
        Notifications.showDeadlines(context, summary, today, alert = true)
        val n = shown(Notifications.ID_DEADLINES)!!
        assertEquals("2 notes are due today or overdue", n.title())
        assertEquals(listOf("Overdue: late", "Today: now"), n.lines())
        assertEquals(Notifications.CHANNEL_DEADLINES, n.notification.channelId)
        assertNotNull(n.notification.contentIntent)
    }

    @Test fun oneNoteIsSingular() {
        Notifications.showDeadlines(context, DeadlineSummary.of(listOf(note("only", today)), today), today, alert = true)
        assertEquals("1 note is due today or overdue", shown(Notifications.ID_DEADLINES)!!.title())
    }

    @Test fun nothingDueMeansNoReminder() {
        Notifications.showDeadlines(context, DeadlineSummary.of(listOf(note("later", today + 3 * DAY_MS)), today), today, alert = true)
        assertNull(shown(Notifications.ID_DEADLINES, expected = false))
    }

    @Test fun longListsAreCutWithAMoreLine() {
        val notes = (1..8).map { note("n$it", today - it * DAY_MS, position = it.toDouble()) }
        Notifications.showDeadlines(context, DeadlineSummary.of(notes, today), today, alert = true)
        val n = shown(Notifications.ID_DEADLINES)!!
        assertEquals(5, n.lines().size)
        assertEquals("+3 more", n.notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
    }

    @Test fun aShowingReminderFollowsTheNotes() = runTest {
        val prefs = ExtrasPrefs(MemoryState()).also { it.setReminders(true) }
        val a = note("a", today - DAY_MS, position = 1.0); val b = note("b", today, position = 2.0)
        Notifications.showDeadlines(context, DeadlineSummary.of(listOf(a, b), today), today, alert = true)

        Extras.refresh(context, prefs, listOf(a.copy(completed = true), b))            // a is done
        assertEquals("1 note is due today or overdue", shown(Notifications.ID_DEADLINES)!!.title())
        assertEquals(listOf("Today: b"), shown(Notifications.ID_DEADLINES)!!.lines().ifEmpty { listOf("Today: b") })

        Extras.refresh(context, prefs, listOf(a.copy(completed = true), b.copy(completed = true)))   // all done
        assertNull(shown(Notifications.ID_DEADLINES, expected = false))
    }

    @Test fun aRefreshNeverCreatesAReminderThatWasNotShown() = runTest {
        val prefs = ExtrasPrefs(MemoryState()).also { it.setReminders(true) }
        Extras.refresh(context, prefs, listOf(note("due", today)))
        assertNull("only the daily check posts the reminder", shown(Notifications.ID_DEADLINES, expected = false))
    }

    @Test fun remindersOffLeavesTheShownReminderAlone() = runTest {
        val prefs = ExtrasPrefs(MemoryState())                                          // all switches off
        Notifications.showDeadlines(context, DeadlineSummary.of(listOf(note("due", today)), today), today, alert = true)
        Extras.refresh(context, prefs, emptyList())
        assertNotNull(shown(Notifications.ID_DEADLINES))
    }

    @Test fun countOnTheIconFollowsTheOpenNotes() = runTest {
        val prefs = ExtrasPrefs(MemoryState()).also { it.setCountBadge(true) }
        Extras.refresh(context, prefs, listOf(note("a"), note("b"), note("c", completed = true)))
        val n = shown(Notifications.ID_COUNT)!!
        assertEquals("2 open notes", n.title())
        assertEquals(2, n.notification.number)
        assertEquals(Notifications.CHANNEL_COUNT, n.notification.channelId)
        assertEquals(Notification.VISIBILITY_SECRET, n.notification.visibility)         // nothing on the lock screen

        Extras.refresh(context, prefs, listOf(note("a")))
        assertEquals("1 open note", shown(Notifications.ID_COUNT)!!.title())

        Extras.refresh(context, prefs, listOf(note("a", completed = true)))             // none open
        assertNull(shown(Notifications.ID_COUNT, expected = false))
    }

    @Test fun countOffRemovesIt() = runTest {
        val prefs = ExtrasPrefs(MemoryState()).also { it.setCountBadge(true) }
        Extras.refresh(context, prefs, listOf(note("a")))
        assertTrue(shown(Notifications.ID_COUNT) != null)
        prefs.setCountBadge(false)
        Extras.refresh(context, prefs, listOf(note("a")))
        assertFalse(shown(Notifications.ID_COUNT, expected = false) != null)
    }
}
