package com.chaosnoteviewer.extras

import com.chaosnoteviewer.data.NoteEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// What a reminder notification of one note says. Plain Kotlin, so it is testable without a phone.
object ReminderText {
    // the deadline as a short line: "Due today", "Overdue since Nov 15"
    fun deadline(
        note: NoteEntity,
        startOfToday: Long,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val due = note.dueDate ?: return ""
        if (due >= startOfToday) return "Due today"
        val day = Instant.ofEpochMilli(due).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d", locale))
        return "Overdue since $day"
    }

    // the first line of the description that has something in it, "" when there is none
    fun firstLine(description: String): String =
        description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    // The notification of a note is posted again under the same id, so it replaces itself instead of stacking.
    // Kept clear of the small ids of the summary and of the other notifications.
    private const val ID_BASE = 1_000
    fun notificationId(noteId: String): Int = ID_BASE + (noteId.hashCode() and 0x3fff_ffff)
}

// The notes a morning reminder is about, in the order they are notified: what is due today first, then what is
// overdue, the most recently missed first. Only the first [limit] get a notification of their own.
fun DeadlineSummary.forNotifications(limit: Int = 8): Pair<List<NoteEntity>, Int> {
    val ordered = today + overdue.sortedByDescending { it.dueDate }
    return ordered.take(limit) to (ordered.size - limit).coerceAtLeast(0)
}
