package com.futurepath.actionbox.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()
    private val debugEventDao = AppDatabase.getInstance(context).notificationDebugEventDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    fun observeDebugEvents(): Flow<List<NotificationDebugEvent>> = debugEventDao.observeAll()

    suspend fun logDebugEvent(event: NotificationDebugEvent) {
        debugEventDao.insert(event)
    }

    /**
     * Dedup is entirely enforced by the two unique indices on [NotificationEntity] (see
     * [NotificationDao.insert]): a notification only fails to insert if the same
     * notificationKey, or the same sourceApp/sender/text/timestamp combination, is already
     * stored. Returns whether the row was actually inserted, so the caller can log the
     * real outcome (captured vs. ignored as a duplicate).
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long): Boolean {
        val rowId = dao.insert(
            NotificationEntity(
                notificationKey = notificationKey,
                sourceApp = sourceApp,
                sender = sender,
                text = text,
                timestamp = timestamp
            )
        )
        return rowId != -1L
    }

    companion object {
        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
