package com.futurepath.actionbox.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Fires once, at the user's configured time (see [ReminderScheduler]), and posts a single
 * notification summarizing counts across the actionable categories (see [DigestFormatter]) —
 * skipped entirely if there's nothing to report. Always reschedules itself for the following
 * day before finishing, turning one OneTimeWorkRequest into an ongoing daily chain — see
 * [ReminderScheduler.scheduleDigest]'s doc for why a self-rescheduling one-shot is used instead
 * of a WorkManager PeriodicWorkRequest.
 */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository.getInstance(applicationContext)

        // Disabling the reminders toggle cancels this chain via ReminderScheduler already —
        // this is just a defensive stop against a run that was already queued when the toggle
        // flipped, so it doesn't post a stray digest (and, more importantly, doesn't
        // reschedule itself and keep the chain alive after the user turned it off).
        if (settings.digestsEnabled.first()) {
            val repository = NotificationRepository.getInstance(applicationContext)
            DigestFormatter.format(repository.getAllOnce())?.let { text ->
                ReminderNotifications.postDigest(applicationContext, text)
            }
            ReminderScheduler.scheduleDigest(applicationContext, settings.digestTime.first())
        }

        return Result.success()
    }
}
