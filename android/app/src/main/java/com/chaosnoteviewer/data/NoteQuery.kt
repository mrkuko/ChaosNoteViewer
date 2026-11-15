package com.chaosnoteviewer.data

import java.text.Collator
import java.time.LocalDate
import java.time.ZoneId

// Which notes the list shows and in what order. Same rules as the desktop AppController (matches, compare).

enum class NoteFilter(val label: String) {
    OPEN("Open"),
    COMPLETED("Completed"),
    ALL("All"),
    // open and overdue, or due within IMPORTANT_DAYS days (today included)
    IMPORTANT("Important");

    companion object {
        const val IMPORTANT_DAYS = 3
    }
}

// The order of the list. OWN = your own order (drag to reorder); any other is a forced order and turns
// dragging off. Stored per device in sync_state as sort_field = ordinal, sort_desc = "1"/"0".
enum class SortField(val label: String, val ascending: String, val descending: String) {
    OWN("Your order", "", ""),
    NAME("Name", "A–Z", "Z–A"),
    DEADLINE("Deadline", "Closest first", "Furthest first"),
    CREATED("Date created", "Oldest first", "Newest first");

    // choosing a field starts with its natural direction: A-Z, closest first, newest first
    val defaultDesc: Boolean get() = this == CREATED
}

object NoteQuery {
    const val DAY_MS = 86_400_000L

    // local midnight of today, the unit of due_date
    fun startOfToday(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    fun matches(n: NoteEntity, filter: NoteFilter, startOfToday: Long): Boolean = when (filter) {
        NoteFilter.OPEN -> !n.completed
        NoteFilter.COMPLETED -> n.completed
        NoteFilter.ALL -> true
        NoteFilter.IMPORTANT -> !n.completed && n.dueDate != null &&
            n.dueDate < startOfToday + NoteFilter.IMPORTANT_DAYS * DAY_MS
    }

    // deadline day before today and still open
    fun isOverdue(n: NoteEntity, startOfToday: Long): Boolean =
        !n.completed && n.dueDate != null && n.dueDate < startOfToday

    // [notes] come in queue order (position). Notes without a deadline go last in both deadline
    // directions; ties keep your own order.
    fun shown(
        notes: List<NoteEntity>,
        filter: NoteFilter,
        sort: SortField,
        desc: Boolean,
        startOfToday: Long,
        collator: Collator = Collator.getInstance(),
    ): List<NoteEntity> {
        val rows = notes.filter { matches(it, filter, startOfToday) }
        if (sort == SortField.OWN)
            return rows
        val dir = if (desc) -1 else 1
        val cmp = Comparator<NoteEntity> { a, b ->
            val d = when (sort) {
                SortField.NAME -> collator.compare(a.title, b.title) * dir
                SortField.DEADLINE -> when {
                    a.dueDate != null && b.dueDate != null -> a.dueDate.compareTo(b.dueDate) * dir
                    a.dueDate != null -> -1
                    b.dueDate != null -> 1
                    else -> 0
                }
                SortField.CREATED -> a.createdAt.compareTo(b.createdAt) * dir
                SortField.OWN -> 0
            }
            if (d != 0) d else a.position.compareTo(b.position)
        }
        return rows.sortedWith(cmp)
    }
}
