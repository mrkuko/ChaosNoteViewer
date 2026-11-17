package com.chaosnoteviewer.ui

import com.chaosnoteviewer.data.NoteEntity

// A note that was just completed (or reopened) by a swipe. It is changed in the database at once, but its row
// stays in the list for a few seconds as a "ghost" with an Undo, even when the current filter would hide it.
data class RecentChange(
    val note: NoteEntity,          // the note as it was before the swipe
    val toCompleted: Boolean,      // what the swipe did
    val index: Int,                // where the row was in the list, to put the ghost back in the same place
)

object RecentChanges {
    // [shown] is the list for the current filter. Every recent change whose note dropped out of it (the Open
    // filter hides a note once it is completed) gets its row back at the old place; one that is still there
    // stays where it is.
    fun apply(shown: List<NoteEntity>, recent: Map<String, RecentChange>): List<NoteEntity> {
        if (recent.isEmpty())
            return shown
        val out = shown.toMutableList()
        val present = out.mapTo(HashSet()) { it.id }
        for (change in recent.values.filter { it.note.id !in present }.sortedBy { it.index })
            out.add(change.index.coerceIn(0, out.size), change.note.copy(completed = change.toCompleted))
        return out
    }

    // id -> what the swipe did, for the rows that are ghosts right now
    fun ghosts(recent: Map<String, RecentChange>): Map<String, Boolean> =
        recent.mapValues { it.value.toCompleted }
}
