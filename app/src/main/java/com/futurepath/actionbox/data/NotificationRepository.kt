package com.futurepath.actionbox.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    /**
     * Dedup is entirely enforced by the two unique indices on [NotificationEntity] (see
     * [NotificationDao.insert]) rather than a time-window heuristic here: a prior
     * "same content within N seconds" check was found to drop genuinely different messages
     * that arrived in quick succession, since it treated recency alone as evidence of a
     * duplicate. Requiring the message's own timestamp (not capture time) plus identical
     * text to match exactly avoids that false positive.
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long) {
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
            Log.d(
                TAG,
                "Insert ignored (duplicate): sourceApp=$sourceApp sender=$sender " +
                    "timestamp=$timestamp key=$notificationKey"
            )
        } else {
            Log.d(TAG, "Captured notification id=$rowId sourceApp=$sourceApp timestamp=$timestamp key=$notificationKey")
        }
    }

    companion object {
        private const val TAG = "ActionBoxRepository"

        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
