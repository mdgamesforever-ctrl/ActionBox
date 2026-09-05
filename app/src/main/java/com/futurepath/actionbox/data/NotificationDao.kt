package com.futurepath.actionbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    /**
     * IGNORE relies on two unique indices on [NotificationEntity]:
     *  - notificationKey: catches a repeat callback for the exact same
     *    StatusBarNotification (same system-assigned key).
     *  - (sourceApp, sender, text, timestamp): catches the case where Android/the source
     *    app reposts the same logical message under a different key, but the extracted
     *    content and message-level timestamp are identical.
     * Both are enforced as DB constraints rather than a separate check-then-insert, so
     * concurrent onNotificationPosted calls can't both slip past a check and both insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    suspend fun getAll(): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM captured_notifications")
    suspend fun count(): Int
}
