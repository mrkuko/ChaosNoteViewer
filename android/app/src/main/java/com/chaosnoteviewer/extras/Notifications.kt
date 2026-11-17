package com.chaosnoteviewer.extras

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chaosnoteviewer.LaunchRequest
import com.chaosnoteviewer.MainActivity
import com.chaosnoteviewer.R
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteFilter
import com.chaosnoteviewer.data.NoteQuery

// The reminders of the app: one notification for every note that is due today or overdue, like a message from a
// social app (the note title, a line of its text, when it is due), all in one group. They only show what the user
// wrote in the notes, nothing from the cloud.
object Notifications {
    const val CHANNEL_DEADLINES = "deadlines"
    // The notification that heads the group. It stays as long as any reminder is showing: Android removes every
    // notification of a group when its summary is cancelled, and with a single reminder it shows that one alone.
    const val ID_SUMMARY = 2
    const val GROUP = "deadlines"
    private const val LEGACY_CHANNEL_COUNT = "open_count"

    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    // the user refused the permission dialog (or Android won't ask again): only the system settings can change it
    fun permissionDenied(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE).getBoolean("asked", false)

    fun rememberAsked(context: Context) {
        context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
    }

    private fun ensureChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DEADLINES, "Deadlines", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reminder for every note that is due today or overdue"
            },
        )
    }

    // The permanent "N open notes" notification of older versions is gone; deleting its channel also removes the
    // notification that may still be showing. Called at every start, it costs nothing.
    fun removeLegacy(context: Context) {
        context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(LEGACY_CHANNEL_COUNT)
    }

    private fun openApp(context: Context, request: LaunchRequest, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, request.toIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun doneAction(context: Context, note: NoteEntity): NotificationCompat.Action {
        val intent = Intent(context, NoteActionReceiver::class.java)
            .setAction(NoteActionReceiver.ACTION_DONE)
            .putExtra(NoteActionReceiver.EXTRA_NOTE_ID, note.id)
        val pending = PendingIntent.getBroadcast(
            context, ReminderText.notificationId(note.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, "Done", pending).build()
    }

    // ---- deadlines ----

    // The reminders of [summary]: a notification for each of the first notes, a summary above them when there is
    // above them, and no notification for a note that no longer belongs here (done, deleted, deadline moved).
    // [alert] false = silent (a refresh because the notes changed, not the morning check).
    fun showDeadlines(context: Context, summary: DeadlineSummary, startOfToday: Long, alert: Boolean) {
        if (summary.isEmpty || !canPost(context)) {
            cancelDeadlines(context)
            return
        }
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        val (notes, more) = summary.forNotifications()

        val keep = notes.map { ReminderText.notificationId(it.id) }.toSet()
        for (active in shownReminders(context))
            if (active.id !in keep && active.id != ID_SUMMARY) manager.cancel(active.id)

        for (note in notes) {
            val line = ReminderText.deadline(note, startOfToday)
            val text = ReminderText.firstLine(note.description)
            val builder = NotificationCompat.Builder(context, CHANNEL_DEADLINES)
                .setSmallIcon(R.drawable.ic_stat_notes)
                .setContentTitle(note.title)
                .setContentText(text.ifEmpty { line })
                .setContentIntent(openApp(context, LaunchRequest(noteId = note.id), ReminderText.notificationId(note.id)))
                .addAction(doneAction(context, note))
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alert)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setShowWhen(false)
            if (text.isNotEmpty()) builder.setSubText(line)
            if (note.description.isNotBlank()) builder.setStyle(NotificationCompat.BigTextStyle().bigText(note.description.trim()))
            manager.notify(ReminderText.notificationId(note.id), builder.build())
        }

        val style = NotificationCompat.InboxStyle().also { s ->
            notes.forEach { s.addLine(it.title) }
            if (more > 0) s.setSummaryText("+$more more")
        }
        manager.notify(
            ID_SUMMARY,
            NotificationCompat.Builder(context, CHANNEL_DEADLINES)
                .setSmallIcon(R.drawable.ic_stat_notes)
                .setContentTitle(if (summary.total == 1) "1 note is due today or overdue" else "${summary.total} notes are due today or overdue")
                .setStyle(style)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentIntent(openApp(context, LaunchRequest(filter = NoteFilter.IMPORTANT), 20))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build(),
        )
    }

    // every reminder that is on the screen now (the summary included)
    private fun shownReminders(context: Context) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.notification.group == GROUP }

    fun cancelDeadlines(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        shownReminders(context).forEach { manager.cancel(it.id) }
    }

    fun deadlinesShown(context: Context): Boolean = shownReminders(context).isNotEmpty()
}
