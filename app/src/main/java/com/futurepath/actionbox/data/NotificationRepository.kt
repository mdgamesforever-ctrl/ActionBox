package com.futurepath.actionbox.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long) {
        dao.insert(
            NotificationEntity(
                notificationKey = notificationKey,
                sourceApp = sourceApp,
                sender = sender,
                text = text,
                timestamp = timestamp
            )
        )
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
