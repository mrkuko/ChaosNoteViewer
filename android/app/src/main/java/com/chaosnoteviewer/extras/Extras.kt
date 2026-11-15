package com.chaosnoteviewer.extras

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteQuery

// Everything outside the app window that follows the notes: the home screen widget, the count on the icon and the
// deadline reminder that is already showing. Called (debounced) whenever the notes change.
object Extras {
    suspend fun refresh(context: Context, prefs: ExtrasPrefs, alive: List<NoteEntity>) {
        // no widget placed = nothing to do; any other failure is logged, a widget must never break the rest
        runCatching { NotesWidget().updateAll(context) }.onFailure { Log.w("Extras", "widget update failed", it) }

        val open = alive.count { !it.completed }
        if (prefs.countBadge()) Notifications.showCount(context, open) else Notifications.cancelCount(context)

        // a reminder that is on the screen follows the notes: done notes disappear from it, it goes away when none is left
        if (prefs.reminders() && Notifications.deadlinesShown(context)) {
            val today = NoteQuery.startOfToday()
            Notifications.showDeadlines(context, DeadlineSummary.of(alive, today), today, alert = false)
        }
    }

    // the switches were changed in Settings
    suspend fun applySwitches(context: Context, prefs: ExtrasPrefs, alive: List<NoteEntity>) {
        if (prefs.reminders()) DeadlineWorker.schedule(context) else {
            DeadlineWorker.cancel(context)
            Notifications.cancelDeadlines(context)
        }
        refresh(context, prefs, alive)
    }
}
