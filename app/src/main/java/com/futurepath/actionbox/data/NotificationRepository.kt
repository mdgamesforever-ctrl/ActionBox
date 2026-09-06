package com.futurepath.actionbox.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

data class CaptureOutcome(
    val wasInserted: Boolean,
    // Human-readable explanation of why the insert was ignored, or null if it succeeded.
    val conflictDetail: String?
)

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()
    private val debugEventDao = AppDatabase.getInstance(context).notificationDebugEventDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    fun observeCapturedCount(): Flow<Int> = dao.observeCount()

    fun observeDebugEvents(): Flow<List<NotificationDebugEvent>> = debugEventDao.observeAll()

    suspend fun logDebugEvent(event: NotificationDebugEvent) {
        debugEventDao.insert(event)
    }

    /**
     * Delegates the whole check-then-insert sequence to [NotificationDao.captureIfNew],
     * which runs it as a single Room transaction so concurrent calls can't race each other
     * (see that method's doc). Two checks run inside that transaction before the insert:
     *  1. Bounded-recency content match: the same real message can reach
     *     onNotificationPosted via two genuinely different StatusBarNotification postings
     *     (rich MessagingStyle vs. plain compatibility) with two different — correctly
     *     different — notificationKeys and timestamps from two different clocks, so an
     *     identical-text match within [CROSS_SOURCE_WINDOW_MS] is treated as one event.
     *  2. Same notificationKey + identical text, for a repeat outside that window. Matching
     *     key with *different* text is never treated as a duplicate on its own — apps like
     *     Messenger/WhatsApp reuse one key for an entire conversation thread, so key alone
     *     doesn't identify a specific message.
     * This method then only turns the result into a human-readable explanation.
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long, receivedAt: Long): CaptureOutcome {
        val result = dao.captureIfNew(
            notificationKey = notificationKey,
            sourceApp = sourceApp,
            sender = sender,
            text = text,
            timestamp = timestamp,
            capturedAt = receivedAt,
            recentWindowMs = CROSS_SOURCE_WINDOW_MS
        )

        if (result.insertedRowId != -1L) {
            return CaptureOutcome(wasInserted = true, conflictDetail = null)
        }

        val matched = result.matchedRow ?: return CaptureOutcome(
            wasInserted = false,
            conflictDetail = "insert was ignored but no matching row was found"
        )
        val reason = when (result.matchReason) {
            "content-window" -> "matched existing row id=${matched.id} by content within " +
                "${CROSS_SOURCE_WINDOW_MS / 1000}s (timestamp differs by ${timestamp - matched.timestamp}ms, " +
                "likely a different extraction path for the same event)"
            "key+text" -> "matched existing row id=${matched.id} by notificationKey + identical text"
            "exact-content-index" -> "matched existing row id=${matched.id} by exact content+timestamp"
            else -> "matched existing row id=${matched.id}"
        }
        return CaptureOutcome(
            wasInserted = false,
            conflictDetail = "$reason, captured ${ageDescription(receivedAt - matched.capturedAt)} ago"
        )
    }

    private fun ageDescription(ageMs: Long): String = when {
        ageMs < 60_000 -> "%.1fs".format(ageMs / 1000.0)
        ageMs < 3_600_000 -> "%.1fm".format(ageMs / 60_000.0)
        else -> "%.1fh".format(ageMs / 3_600_000.0)
    }

    companion object {
        // Covers the gap between a message's own MessagingStyle timestamp and the device
        // notification post time used when a second, plain-text posting of the same event
        // has no MessagingStyle data (observed gap in testing: ~2.5s). Short enough that an
        // identical message sent again minutes/hours later is unaffected.
        private const val CROSS_SOURCE_WINDOW_MS = 10_000L

        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
