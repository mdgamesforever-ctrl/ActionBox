package com.futurepath.actionbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    /**
     * IGNORE relies on the unique index on [NotificationEntity.notificationKey]: if a
     * notification with the same system-assigned key is already stored, this insert is a
     * silent no-op. Enforcing the dedup as a DB constraint (rather than a separate
     * check-then-insert) makes it atomic, so two near-simultaneous onNotificationPosted
     * calls for the same notification can't both slip past a check and both insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    suspend fun getAll(): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM captured_notifications")
    suspend fun count(): Int

    /**
     * Fallback dedup for when Android assigns two different StatusBarNotification keys to
     * what is really the same message (observed with WhatsApp). Only looks at the single
     * most recent row for this exact sourceApp/sender/text combination, so a message with
     * identical text sent again hours later is unaffected — only the immediately preceding
     * capture of the same content is considered.
     */
    @Query(
        """
        SELECT timestamp FROM captured_notifications
        WHERE sourceApp = :sourceApp AND sender = :sender AND text = :text
        ORDER BY timestamp DESC
        LIMIT 1
        """
    )
    suspend fun mostRecentTimestampFor(sourceApp: String, sender: String, text: String): Long?
}
