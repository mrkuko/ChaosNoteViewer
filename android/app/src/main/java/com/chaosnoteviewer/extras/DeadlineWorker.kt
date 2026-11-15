package com.chaosnoteviewer.extras

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.data.NoteQuery
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// Once a day, about 8:00 local time: a notification with the notes that are due today or overdue.
class DeadlineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ChaosApp
        // the user may have turned it off since this was scheduled
        if (!ExtrasPrefs(app.notes).reminders()) return Result.success()
        val today = NoteQuery.startOfToday()
        Notifications.showDeadlines(app, DeadlineSummary.of(app.notes.alive.first(), today), today, alert = true)
        return Result.success()
    }

    companion object {
        private const val WORK = "deadline-reminder"
        private val AT = LocalTime.of(8, 0)

        // time from [now] until the next 8:00
        fun delayUntilNext(now: LocalDateTime): Duration {
            var next = now.toLocalDate().atTime(AT)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }

        // Starts the daily check, or keeps the one that is there. It must never cancel a running check: when the system
        // starts this app only to run it, onCreate calls this too.
        fun schedule(context: Context) {
            val delay = delayUntilNext(LocalDateTime.now(ZoneId.systemDefault()))
            val request = PeriodicWorkRequestBuilder<DeadlineWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
