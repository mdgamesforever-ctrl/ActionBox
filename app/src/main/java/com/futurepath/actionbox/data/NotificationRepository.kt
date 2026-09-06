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
     * Dedup has two layers:
     *  1. A bounded-recency content check (this method, before the insert): the same real
     *     message can reach onNotificationPosted via two genuinely different
     *     StatusBarNotification postings — a rich MessagingStyle one and a separate plain
     *     compatibility one for the same event — with two different (correctly different)
     *     notificationKeys and timestamps from two different clocks (message-own-time vs.
     *     this device's notification post time), so they will not match exactly. Identical
     *     text within [CROSS_SOURCE_WINDOW_MS] is treated as the same event.
     *  2. The two unique indices on [NotificationEntity] (see [NotificationDao.insert]),
     *     enforced atomically by the DB, catch an exact repeat (same key, or identical
     *     content down to the same timestamp) even under concurrent calls.
     * When either layer rejects the capture, a follow-up lookup explains *why* — which
     * constraint matched and how long ago that row was originally captured — since "it was
     * a duplicate" alone doesn't distinguish a genuine repeat from stale test data.
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long, receivedAt: Long): CaptureOutcome {
        val recentMatch = dao.findRecentByContent(
            sourceApp = sourceApp,
            sender = sender,
            text = text,
            minTimestamp = timestamp - CROSS_SOURCE_WINDOW_MS,
            maxTimestamp = timestamp + CROSS_SOURCE_WINDOW_MS
        )
        if (recentMatch != null) {
            return CaptureOutcome(
                wasInserted = false,
                conflictDetail = "matched existing row id=${recentMatch.id} by content within " +
                    "${CROSS_SOURCE_WINDOW_MS / 1000}s (timestamp differs by ${timestamp - recentMatch.timestamp}ms, " +
                    "likely a different extraction path for the same event), captured ${ageDescription(receivedAt - recentMatch.capturedAt)} ago"
            )
        }

        val rowId = dao.insert(
            NotificationEntity(
                notificationKey = notificationKey,
                sourceApp = sourceApp,
                sender = sender,
                text = text,
                timestamp = timestamp,
                capturedAt = receivedAt
            )
        )
        if (rowId != -1L) {
            return CaptureOutcome(wasInserted = true, conflictDetail = null)
        }

        val byKey = dao.findByKey(notificationKey)
        val byContent = dao.findByContent(sourceApp, sender, text, timestamp)
        val detail = buildString {
            byKey?.let {
                append("matched existing row id=${it.id} by notificationKey, captured ${ageDescription(receivedAt - it.capturedAt)} ago")
            }
            byContent?.let {
                if (isNotEmpty()) append("; ")
                append("matched existing row id=${it.id} by content+timestamp, captured ${ageDescription(receivedAt - it.capturedAt)} ago")
            }
            if (isEmpty()) append("insert was ignored but no matching row was found")
        }
        return CaptureOutcome(wasInserted = false, conflictDetail = detail)
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
