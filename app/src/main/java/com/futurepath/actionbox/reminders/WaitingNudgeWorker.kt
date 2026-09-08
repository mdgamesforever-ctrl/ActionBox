package com.futurepath.actionbox.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.data.effectiveState
import kotlinx.coroutines.flow.first

/**
 * Runs periodically (see [ReminderScheduler.scheduleWaitingNudges]) and posts one "Still
 * waiting on X?" notification covering every WAITING item whose implied timeframe (see
 * [WaitingTimeframeResolver]) has passed since it arrived and that hasn't already been nudged —
 * [com.futurepath.actionbox.data.NotificationEntity.waitingNudgedAt] is set on every item this
 * run covers, so each one is only ever nudged once, even if it's still WAITING on the next scan.
 */
class WaitingNudgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository.getInstance(applicationContext)
        if (!settings.digestsEnabled.first()) return Result.success()

        val repository = NotificationRepository.getInstance(applicationContext)
        val now = System.currentTimeMillis()
        val overdue = repository.getAllOnce().filter { notification ->
            notification.effectiveState == ClassifiedState.WAITING &&
                notification.waitingNudgedAt == null &&
                WaitingTimeframeResolver.isOverdue(notification.extractedDate, notification.timestamp, now)
        }

        if (overdue.isNotEmpty()) {
            ReminderNotifications.postWaitingNudge(applicationContext, nudgeText(overdue.map { it.sender }))
            overdue.forEach { repository.markWaitingNudged(it.id, now) }
        }

        return Result.success()
    }

    private fun nudgeText(rawSenders: List<String>): String {
        val senders = rawSenders.map { it.ifBlank { "someone" } }.distinct()
        return when {
            senders.size == 1 -> "Still waiting on ${senders.first()}?"
            senders.size <= 3 -> "Still waiting on ${senders.joinToString(", ")}?"
            else -> "Still waiting on ${senders.size} people?"
        }
    }
}
