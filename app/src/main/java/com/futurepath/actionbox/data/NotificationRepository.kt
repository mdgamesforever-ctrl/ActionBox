package com.futurepath.actionbox.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    /**
     * Android frequently re-fires onNotificationPosted for the same logical notification
     * (e.g. a messaging app updating an existing notification in place). Treat any
     * notification with the same app/sender/text arriving within [DUPLICATE_WINDOW_MS] of
     * an existing row as the same event and skip it.
     */
    suspend fun capture(sourceApp: String, sender: String, text: String, timestamp: Long) {
        val isDuplicate = dao.existsSimilar(
            sourceApp = sourceApp,
            sender = sender,
            text = text,
            minTimestamp = timestamp - DUPLICATE_WINDOW_MS,
            maxTimestamp = timestamp + DUPLICATE_WINDOW_MS
        )
        if (isDuplicate) return

        dao.insert(
            NotificationEntity(
                sourceApp = sourceApp,
                sender = sender,
                text = text,
                timestamp = timestamp
            )
        )
    }

    companion object {
        private const val DUPLICATE_WINDOW_MS = 2000L

        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
