package com.chaosnoteviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.chaosnoteviewer.data.NoteFilter

// Where the app should go when it is opened from outside: a note (home screen widget), a new note (widget "+"),
// or one of the lists (notifications). Travels as the extras of the launch Intent.
data class LaunchRequest(val noteId: String? = null, val newNote: Boolean = false, val filter: NoteFilter? = null) {
    val isEmpty: Boolean get() = noteId == null && !newNote && filter == null

    fun toIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // a distinct data URI, so every request has its own PendingIntent
            .setData(Uri.parse("chaosnote://open/${noteId ?: if (newNote) "new" else filter?.name ?: "list"}"))
            .also {
                noteId?.let { id -> it.putExtra(EXTRA_NOTE_ID, id) }
                if (newNote) it.putExtra(EXTRA_NEW_NOTE, true)
                filter?.let { f -> it.putExtra(EXTRA_FILTER, f.name) }
            }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NEW_NOTE = "new_note"
        const val EXTRA_FILTER = "filter"

        fun from(intent: Intent?): LaunchRequest {
            if (intent == null) return LaunchRequest()
            return LaunchRequest(
                noteId = intent.getStringExtra(EXTRA_NOTE_ID),
                newNote = intent.getBooleanExtra(EXTRA_NEW_NOTE, false),
                filter = intent.getStringExtra(EXTRA_FILTER)?.let { name -> NoteFilter.entries.firstOrNull { it.name == name } },
            )
        }
    }
}
