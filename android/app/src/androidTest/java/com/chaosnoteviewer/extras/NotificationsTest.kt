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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The real notifications of this phone: one per note, what they say, the group, and how they follow the notes.
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val today = NoteQuery.startOfToday()

    private fun note(title: String, due: Long? = null, completed: Boolean = false, position: Double = 1.0, description: String = "") =
        NoteEntity(id = title, title = title, description = description, completed = completed, position = position, dueDate = due, createdAt = 0, updatedAt = 0)

    // The system posts and cancels notifications a moment after the call: wait until it is as expected (or give up).
    private fun shown(id: Int, expected: Boolean = true): StatusBarNotification? {
        repeat(30) {
            val n = manager.activeNotifications.firstOrNull { it.id == id }
            if ((n != null) == expected) return n
            Thread.sleep(100)
        }
        return manager.activeNotifications.firstOrNull { it.id == id }
    }
    private fun idOf(title: String) = ReminderText.notificationId(title)
    private fun reminders() = manager.activeNotifications.filter { it.notification.group == Notifications.GROUP }
    // wait until exactly this many reminders (summary included) are showing
    private fun waitForCount(count: Int): List<StatusBarNotification> {
        repeat(30) {
            if (reminders().size == count) return reminders()
            Thread.sleep(100)
        }
        return reminders()
    }

    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun StatusBarNotification.text() = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun StatusBarNotification.subText() = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    private fun StatusBarNotification.lines() =
        notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()

    @Before @After fun clear() {
        Notifications.cancelDeadlines(context)
    }

    private fun post(vararg notes: NoteEntity, alert: Boolean = true) =
        Notifications.showDeadlines(context, DeadlineSummary.of(notes.toList(), today), today, alert)

    @Test fun everyNoteGetsItsOwnNotification() {
        post(note("late", today - 2 * DAY_MS, description = "Call them\nsecond line"), note("now", today), note("later", today + DAY_MS))
        val shownNow = waitForCount(3)                                             // two notes and the summary
        assertEquals(setOf(idOf("late"), idOf("now"), Notifications.ID_SUMMARY), shownNow.map { it.id }.toSet())

        val late = shown(idOf("late"))!!
        assertEquals("late", late.title())                                         // the note's own title
        assertEquals("Call them", late.text())                                     // a line of its text
        assertTrue(late.subText()!!.startsWith("Overdue since"))
        assertEquals(Notifications.CHANNEL_DEADLINES, late.notification.channelId)
        assertNotNull(late.notification.contentIntent)
        assertEquals(listOf("Done"), late.notification.actions.map { it.title.toString() })
    }

    @Test fun aNoteWithoutTextSaysWhenItIsDue() {
        post(note("plain", today))
        val n = shown(idOf("plain"))!!
        assertEquals("plain", n.title())
        assertEquals("Due today", n.text())
        assertNull(n.subText())
    }

    @Test fun oneNoteStillHasItsSummaryAsAnchor() {
        // Android shows a lone reminder by itself, but cancelling the summary would take the reminder with it
        post(note("only", today))
        assertEquals(setOf(idOf("only"), Notifications.ID_SUMMARY), waitForCount(2).map { it.id }.toSet())
        assertEquals("1 note is due today or overdue", shown(Notifications.ID_SUMMARY)!!.title())
    }

    @Test fun severalNotesAreGroupedUnderASummary() {
        post(note("a", today), note("b", today - DAY_MS))
        val summary = shown(Notifications.ID_SUMMARY)!!
        assertEquals("2 notes are due today or overdue", summary.title())
        assertTrue(summary.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(listOf("a", "b"), summary.lines())
    }

    @Test fun nothingDueMeansNoReminder() {
        post(note("later", today + 3 * DAY_MS))
        assertEquals(0, waitForCount(0).size)
    }

    @Test fun onlyTheFirstFewHaveTheirOwnNotification() {
        post(*(1..12).map { note("n$it", today - it * DAY_MS, position = it.toDouble()) }.toTypedArray())
        val all = waitForCount(9)                                                  // 8 notes and the summary
        assertEquals(9, all.size)
        assertEquals("+4 more", shown(Notifications.ID_SUMMARY)!!.notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
    }

    @Test fun aNoteThatIsDoneLosesItsNotification() {
        val a = note("a", today - DAY_MS, position = 1.0); val b = note("b", today, position = 2.0); val c = note("c", today, position = 3.0)
        post(a, b, c)
        waitForCount(4)
        post(a.copy(completed = true), b, c, alert = false)                        // a is done
        val left = waitForCount(3)
        assertEquals(setOf(idOf("b"), idOf("c"), Notifications.ID_SUMMARY), left.map { it.id }.toSet())

        post(a.copy(completed = true), b.copy(completed = true), c, alert = false) // only c left: it must stay
        assertEquals(setOf(idOf("c"), Notifications.ID_SUMMARY), waitForCount(2).map { it.id }.toSet())

        post(a.copy(completed = true), b.copy(completed = true), c.copy(completed = true), alert = false)
        assertEquals(0, waitForCount(0).size)
    }

    @Test fun aShowingReminderFollowsTheNotes() = runTest {
        val prefs = ExtrasPrefs(MemoryState()).also { it.setReminders(true) }
        val a = note("a", today - DAY_MS, position = 1.0); val b = note("b", today, position = 2.0)
        post(a, b)
        waitForCount(3)

        Extras.refresh(context, prefs, listOf(a.copy(completed = true), b))        // a is done
        assertEquals(setOf(idOf("b"), Notifications.ID_SUMMARY), waitForCount(2).map { it.id }.toSet())

        Extras.refresh(context, prefs, listOf(a.copy(completed = true), b.copy(completed = true)))   // all done
        assertEquals(0, waitForCount(0).size)
    }

    @Test fun aRefreshNeverCreatesAReminderThatWasNotShown() = runTest {
        val prefs = ExtrasPrefs(MemoryState()).also { it.setReminders(true) }
        Extras.refresh(context, prefs, listOf(note("due", today)))
        assertEquals("only the daily check posts the reminder", 0, waitForCount(0).size)
    }

    @Test fun remindersOffLeavesTheShownReminderAlone() = runTest {
        val prefs = ExtrasPrefs(MemoryState())                                          // the switch is off
        post(note("due", today))
        shown(idOf("due"))
        Extras.refresh(context, prefs, emptyList())
        assertNotNull(shown(idOf("due")))
    }

    @Test fun theOldOpenNotesCountIsRemoved() {
        manager.createNotificationChannel(android.app.NotificationChannel("open_count", "old", NotificationManager.IMPORTANCE_LOW))
        assertNotNull(manager.getNotificationChannel("open_count"))
        Notifications.removeLegacy(context)
        assertNull(manager.getNotificationChannel("open_count"))
    }

    // ---- the daily check must never cancel itself ----

    private val work get() = androidx.work.WorkManager.getInstance(context)
    private fun reminderInfos() = work.getWorkInfosForUniqueWork("deadline-reminder").get().filter { !it.state.isFinished }

    @Test fun theDailyCheckIsScheduledForAboutEight() {
        DeadlineWorker.cancel(context); work.pruneWork().result.get()
        DeadlineWorker.schedule(context)
        val next = reminderInfos().single().nextScheduleTimeMillis
        val time = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        assertTrue("due about 8:00, not $time", java.time.Duration.between(java.time.LocalTime.of(8, 0), time).abs().toMinutes() <= 2)
        DeadlineWorker.cancel(context)
    }

    @Test fun startingTheAppDoesNotCancelOrMoveTheCheck() {
        DeadlineWorker.cancel(context); work.pruneWork().result.get()
        DeadlineWorker.schedule(context)
        val before = reminderInfos().single()
        repeat(3) { DeadlineWorker.schedule(context) }            // every app start does this
        val after = reminderInfos().single()
        assertEquals("the same job, not cancelled and enqueued again", before.id, after.id)
        assertEquals(before.nextScheduleTimeMillis, after.nextScheduleTimeMillis)
        DeadlineWorker.cancel(context)
    }
}
