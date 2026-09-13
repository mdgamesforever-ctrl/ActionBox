package com.futurepath.actionbox.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.futurepath.actionbox.data.NotificationRepository

/**
 * Runs periodically (see [ReminderScheduler.scheduleSnoozeChecks]) and clears
 * [com.futurepath.actionbox.data.NotificationEntity.snoozedUntil] on everything whose snooze has
 * elapsed since the last check via [NotificationRepository.clearExpiredSnoozes] — that write
 * alone is what brings the item back into the active inbox, since Room's Flow re-emits on it —
 * then posts one notification calling attention to what came back. Unlike the digest/nudge
 * workers, this always runs regardless of the digest settings toggle: snoozing is a direct user
 * action on a specific item, not a background summarization preference.
 *
 * Also runs [NotificationRepository.enforceRecoveryRetentionPolicy] on every tick — the
 * free-tier Handled/Snoozed cleanup for ui/recovery/RecoveryScreen. Piggybacked here rather than
 * a separate WorkManager job: this worker is already the one unconditional, frequent (15-minute)
 * periodic tick in the app, and the cleanup needs exactly that — silent background hygiene with
 * no UI of its own, never a notification (contrast with the snooze-returned notification above,
 * which stays scoped to actual snooze expiry).
 */
class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = NotificationRepository.getInstance(applicationContext)
        val returned = repository.clearExpiredSnoozes()
        if (returned.isNotEmpty()) {
            ReminderNotifications.postSnoozeReturned(applicationContext, returnedText(returned.map { it.sender }))
        }
        repository.enforceRecoveryRetentionPolicy()
        return Result.success()
    }

    private fun returnedText(rawSenders: List<String>): String {
        val senders = rawSenders.map { it.ifBlank { "someone" } }.distinct()
        return when {
            senders.size == 1 -> "Snoozed notification from ${senders.first()} is back"
            senders.size <= 3 -> "Snoozed notifications from ${senders.joinToString(", ")} are back"
            else -> "${senders.size} snoozed notifications are back"
        }
    }
}
