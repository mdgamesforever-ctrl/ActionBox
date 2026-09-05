package com.futurepath.actionbox.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    /**
     * Two layers of dedup:
     *  1. Primary: [NotificationEntity.notificationKey] has a unique index and insert uses
     *     onConflict = IGNORE, so a repeat callback for the exact same StatusBarNotification
     *     (same key) never produces a second row — enforced atomically by the DB.
     *  2. Fallback: WhatsApp (and possibly other apps) can post what's semantically the same
     *     message under two different keys. If the most recent row with the same
     *     sourceApp/sender/text was captured within [RECENT_DUPLICATE_WINDOW_MS], treat this
     *     as the same event rather than scanning all-time history — so identical text sent
     *     again hours later is still captured.
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long) {
        val mostRecentMatch = dao.mostRecentTimestampFor(sourceApp, sender, text)
        if (mostRecentMatch != null && kotlin.math.abs(timestamp - mostRecentMatch) <= RECENT_DUPLICATE_WINDOW_MS) {
            Log.d(
                TAG,
                "Skipping duplicate: sourceApp=$sourceApp sender=$sender key=$notificationKey " +
                    "matches recent capture at $mostRecentMatch (this timestamp=$timestamp)"
            )
            return
        }

        val rowId = dao.insert(
            NotificationEntity(
                notificationKey = notificationKey,
                sourceApp = sourceApp,
                sender = sender,
                text = text,
                timestamp = timestamp
            )
        )

        if (rowId == -1L) {
            Log.d(TAG, "Insert ignored by unique key constraint: notificationKey=$notificationKey already stored")
        } else {
            Log.d(TAG, "Captured notification id=$rowId sourceApp=$sourceApp key=$notificationKey")
        }
    }

    companion object {
        private const val TAG = "ActionBoxRepository"

        // How close two captures of the same sourceApp/sender/text have to be to be treated
        // as one event rather than two separate messages with identical content.
        private const val RECENT_DUPLICATE_WINDOW_MS = 30_000L

        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
