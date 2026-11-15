package com.chaosnoteviewer.extras

import com.chaosnoteviewer.data.NoteEntity

// Which open notes the deadline reminder is about: due today or overdue ("Important" without the next days).
data class DeadlineSummary(val overdue: List<NoteEntity>, val today: List<NoteEntity>) {
    val total: Int get() = overdue.size + today.size
    val isEmpty: Boolean get() = total == 0

    companion object {
        // [startOfToday]: local midnight of today (the unit of due_date). Oldest deadline first, then your order.
        fun of(notes: List<NoteEntity>, startOfToday: Long): DeadlineSummary {
            val open = notes.filter { !it.completed && it.deletedAt == null && it.dueDate != null }
            val order = compareBy<NoteEntity>({ it.dueDate }, { it.position })
            return DeadlineSummary(
                overdue = open.filter { it.dueDate!! < startOfToday }.sortedWith(order),
                today = open.filter { it.dueDate == startOfToday }.sortedWith(order),
            )
        }
    }
}
