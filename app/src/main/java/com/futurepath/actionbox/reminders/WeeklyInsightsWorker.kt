package com.futurepath.actionbox.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Fires weekly (Sunday evening — see [ReminderScheduler.scheduleWeeklyInsights]) and posts a
 * Pro-only summary notification (see [WeeklyInsightsCalculator]/[ReminderNotifications]). Always
 * reschedules itself for the following week before finishing — the same self-rescheduling
 * one-shot pattern as [DigestWorker] — regardless of Pro status, so a later upgrade to Pro
 * doesn't need a fresh schedule to start getting the digest; only the posting itself is gated.
 */
class WeeklyInsightsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository.getInstance(applicationContext)
        if (settings.isPro.first()) {
            val repository = NotificationRepository.getInstance(applicationContext)
            val insights = WeeklyInsightsCalculator.compute(repository.getAllOnce(), System.currentTimeMillis())
            WeeklyInsightsCalculator.summaryText(insights)?.let { text ->
                ReminderNotifications.postWeeklyInsights(applicationContext, text)
            }
        }

        ReminderScheduler.scheduleWeeklyInsights(applicationContext)
        return Result.success()
    }
}
