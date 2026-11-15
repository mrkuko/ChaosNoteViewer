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

// The two notifications of the app. Both only show the number of notes and note titles the user wrote, nothing from
// the cloud; the count one is hidden on the lock screen.
object Notifications {
    const val CHANNEL_DEADLINES = "deadlines"
    const val CHANNEL_COUNT = "open_count"
    const val ID_DEADLINES = 2
    const val ID_COUNT = 1
    private const val MAX_LINES = 5

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
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DEADLINES, "Deadlines", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notes that are due today or overdue"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COUNT, "Open notes count", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet notification that puts the number of open notes on the app icon"
                setShowBadge(true)
            },
        )
    }

    private fun openApp(context: Context, request: LaunchRequest, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, request.toIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---- deadlines ----

    fun deadlineText(note: NoteEntity, startOfToday: Long) =
        (if (NoteQuery.isOverdue(note, startOfToday)) "Overdue: " else "Today: ") + note.title

    fun deadlineTitle(summary: DeadlineSummary) =
        if (summary.total == 1) "1 note is due today or overdue" else "${summary.total} notes are due today or overdue"

    // Posts (or updates) the reminder; [alert] false = silent (a refresh because the notes changed).
    fun showDeadlines(context: Context, summary: DeadlineSummary, startOfToday: Long, alert: Boolean) {
        if (summary.isEmpty || !canPost(context)) {
            cancelDeadlines(context)
            return
        }
        ensureChannels(context)
        val lines = (summary.overdue + summary.today)
        val style = NotificationCompat.InboxStyle().also { s ->
            lines.take(MAX_LINES).forEach { s.addLine(deadlineText(it, startOfToday)) }
            if (lines.size > MAX_LINES) s.setSummaryText("+${lines.size - MAX_LINES} more")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DEADLINES)
            .setSmallIcon(R.drawable.ic_stat_notes)
            .setContentTitle(deadlineTitle(summary))
            .setContentText(deadlineText(lines.first(), startOfToday))
            .setStyle(style)
            .setContentIntent(openApp(context, LaunchRequest(filter = NoteFilter.IMPORTANT), 20))
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(ID_DEADLINES, notification)
    }

    fun cancelDeadlines(context: Context) = NotificationManagerCompat.from(context).cancel(ID_DEADLINES)

    fun deadlinesShown(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ID_DEADLINES }

    // ---- open notes count on the icon ----

    fun showCount(context: Context, openCount: Int) {
        if (openCount == 0 || !canPost(context)) {
            cancelCount(context)
            return
        }
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_COUNT)
            .setSmallIcon(R.drawable.ic_stat_notes)
            .setContentTitle(if (openCount == 1) "1 open note" else "$openCount open notes")
            .setNumber(openCount)                              // the number some launchers show on the icon
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(false)
            .setContentIntent(openApp(context, LaunchRequest(filter = NoteFilter.OPEN), 10))
            .build()
        NotificationManagerCompat.from(context).notify(ID_COUNT, notification)
    }

    fun cancelCount(context: Context) = NotificationManagerCompat.from(context).cancel(ID_COUNT)
}
