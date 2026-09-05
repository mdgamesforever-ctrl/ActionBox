package com.futurepath.actionbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Insert
    suspend fun insert(notification: NotificationEntity): Long

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    suspend fun getAll(): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM captured_notifications")
    suspend fun count(): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM captured_notifications
            WHERE sourceApp = :sourceApp
              AND sender = :sender
              AND text = :text
              AND timestamp BETWEEN :minTimestamp AND :maxTimestamp
        )
        """
    )
    suspend fun existsSimilar(
        sourceApp: String,
        sender: String,
        text: String,
        minTimestamp: Long,
        maxTimestamp: Long
    ): Boolean
}
