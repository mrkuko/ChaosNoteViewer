package com.chaosnoteviewer.extras

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.chaosnoteviewer.ChaosApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// The "Done" button of a reminder notification: completes the note without opening the app.
class NoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
        // the notification goes at once, the change itself is a database write
        NotificationManagerCompat.from(context).cancel(ReminderText.notificationId(id))
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                (context.applicationContext as ChaosApp).notes.setCompleted(id, true)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.chaosnoteviewer.action.DONE"
        const val EXTRA_NOTE_ID = "note_id"
    }
}
