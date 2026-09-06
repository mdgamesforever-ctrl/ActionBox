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
    fun observeCount(): Flow<Int>

    // Diagnostic-only: called after an insert is ignored, to find out which constraint
    // caused it and when that row was originally captured.
    @Query("SELECT * FROM captured_notifications WHERE notificationKey = :notificationKey LIMIT 1")
    suspend fun findByKey(notificationKey: String): NotificationEntity?

    @Query(
        """
        SELECT * FROM captured_notifications
        WHERE sourceApp = :sourceApp AND sender = :sender AND text = :text AND timestamp = :timestamp
        LIMIT 1
        """
    )
    suspend fun findByContent(sourceApp: String, sender: String, text: String, timestamp: Long): NotificationEntity?

    /**
     * Same real message can reach onNotificationPosted via two genuinely different
     * StatusBarNotification postings (e.g. a rich MessagingStyle notification and a
     * separate plain-text compatibility notification for the same event) — different
     * notificationKey, and their timestamps come from different clocks (the message's own
     * timestamp vs. this device's notification post time), so they rarely match exactly.
     * Matching on identical text within a short window catches this without the
     * exact-equality unique index ever seeing it.
     */
    @Query(
        """
        SELECT * FROM captured_notifications
        WHERE sourceApp = :sourceApp AND sender = :sender AND text = :text
          AND timestamp BETWEEN :minTimestamp AND :maxTimestamp
        LIMIT 1
        """
    )
    suspend fun findRecentByContent(sourceApp: String, sender: String, text: String, minTimestamp: Long, maxTimestamp: Long): NotificationEntity?
}
