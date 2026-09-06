package com.futurepath.actionbox.data

import android.content.Context
import com.futurepath.actionbox.classification.NotificationClassifier
import com.futurepath.actionbox.classification.TextNormalizer
import kotlinx.coroutines.flow.Flow

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    /**
     * Delegates the whole check-then-insert sequence to [NotificationDao.captureIfNew],
     * which runs it as a single Room transaction so concurrent calls can't race each other
     * (see that method's doc):
     *  1. Bounded-recency content match: the same real message can reach
     *     onNotificationPosted via two genuinely different StatusBarNotification postings
     *     (rich MessagingStyle vs. plain compatibility) with two different — correctly
     *     different — notificationKeys and timestamps from two different clocks, so an
     *     identical-text match within [CROSS_SOURCE_WINDOW_MS] is treated as one event.
     *  2. Same notificationKey + identical text, for a repeat outside that window. Matching
     *     key with *different* text is never treated as a duplicate on its own — apps like
     *     Messenger/WhatsApp reuse one key for an entire conversation thread, so key alone
     *     doesn't identify a specific message.
     * On a genuine new insert, classification runs immediately (still within the background
     * coroutine the caller launched) and the row is updated with its result.
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long, receivedAt: Long) {
        val normalizedText = TextNormalizer.normalize(text)
        val result = dao.captureIfNew(
            notificationKey = notificationKey,
            sourceApp = sourceApp,
            sender = sender,
            text = text,
            normalizedText = normalizedText,
            timestamp = timestamp,
            capturedAt = receivedAt,
            recentWindowMs = CROSS_SOURCE_WINDOW_MS
        )

        if (result.insertedRowId != -1L) {
            val classification = NotificationClassifier.classify(sourceApp, sender, normalizedText)
            dao.updateClassification(
                id = result.insertedRowId,
                state = classification.state,
                summary = classification.summary,
                date = classification.date,
                confidence = classification.confidence
            )
        }
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
