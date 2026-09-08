package com.futurepath.actionbox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.futurepath.actionbox.classification.ClassifiedState
import kotlinx.coroutines.flow.Flow

data class CaptureAttemptResult(
    val insertedRowId: Long,
    val matchedRow: NotificationEntity?,
    val matchReason: String?
)

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRaw(notification: NotificationEntity): Long

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM captured_notifications ORDER BY timestamp DESC")
    suspend fun getAll(): List<NotificationEntity>

    @Query("SELECT * FROM captured_notifications WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NotificationEntity?

    @Query("SELECT COUNT(*) FROM captured_notifications")
    fun observeCount(): Flow<Int>

    /**
     * Enforces the free-tier retention window (see
     * [com.futurepath.actionbox.data.NotificationRepository.enforceRetentionPolicy]) by
     * deleting anything older than [cutoffTimestamp]. Filters on [NotificationEntity.timestamp]
     * (the message's own time), not [NotificationEntity.capturedAt] (device insert time), so
     * retention is measured from when the notification actually happened.
     */
    @Query("DELETE FROM captured_notifications WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)

    // See NotificationEntity.waitingNudgedAt — marks a WAITING item as already followed up on
    // so com.futurepath.actionbox.reminders.WaitingNudgeWorker never nudges it twice.
    @Query("UPDATE captured_notifications SET waitingNudgedAt = :nudgedAt WHERE id = :id")
    suspend fun markWaitingNudged(id: Long, nudgedAt: Long)

    @Query(
        """
        UPDATE captured_notifications
        SET classifiedState = :state, extractedSummary = :summary, extractedDate = :date,
            confidenceScore = :confidence, isProcessed = 1
        WHERE id = :id
        """
    )
    suspend fun updateClassification(id: Long, state: ClassifiedState, summary: String?, date: String?, confidence: Int)

    // classifiedState is left untouched — see NotificationEntity.correctedState.
    @Query("UPDATE captured_notifications SET correctedState = :state WHERE id = :id")
    suspend fun updateCorrectedState(id: Long, state: ClassifiedState)

    // See NotificationEntity.mlClassifiedState — recorded for comparison only, doesn't touch
    // classifiedState/correctedState.
    @Query("UPDATE captured_notifications SET mlClassifiedState = :state, mlConfidence = :confidence WHERE id = :id")
    suspend fun updateMlClassification(id: Long, state: ClassifiedState, confidence: Int)

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
     * Matching on identical text within a short window catches this without needing the
     * exact-equality unique index to see it.
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

    /**
     * Catches a repeat callback for the same conversation notification reposting identical
     * text outside the recency window above. Deliberately requires text equality — the key
     * alone is not unique per message for apps that reuse one key per conversation thread
     * (see [NotificationEntity]), so a matching key with *different* text must fall through
     * to a normal insert as a new message.
     */
    @Query("SELECT * FROM captured_notifications WHERE notificationKey = :notificationKey AND text = :text LIMIT 1")
    suspend fun findByKeyAndText(notificationKey: String, text: String): NotificationEntity?

    /**
     * Runs the whole check-then-insert sequence as one Room transaction. Room serializes
     * @Transaction suspend functions on the same database against each other (via its
     * internal transaction executor), so two concurrent onNotificationPosted calls can no
     * longer both pass the checks before either one's insert commits — the second call's
     * checks only run after the first call's transaction (checks + insert) has fully
     * completed, so it will see the first call's row.
     */
    @Transaction
    suspend fun captureIfNew(
        notificationKey: String,
        sourceApp: String,
        sender: String,
        text: String,
        normalizedText: String,
        timestamp: Long,
        capturedAt: Long,
        recentWindowMs: Long
    ): CaptureAttemptResult {
        findRecentByContent(
            sourceApp = sourceApp,
            sender = sender,
            text = text,
            minTimestamp = timestamp - recentWindowMs,
            maxTimestamp = timestamp + recentWindowMs
        )?.let {
            return CaptureAttemptResult(insertedRowId = -1L, matchedRow = it, matchReason = "content-window")
        }

        findByKeyAndText(notificationKey, text)?.let {
            return CaptureAttemptResult(insertedRowId = -1L, matchedRow = it, matchReason = "key+text")
        }

        val rowId = insertRaw(
            NotificationEntity(
                notificationKey = notificationKey,
                sourceApp = sourceApp,
                sender = sender,
                text = text,
                normalizedText = normalizedText,
                timestamp = timestamp,
                capturedAt = capturedAt
            )
        )
        if (rowId != -1L) {
            return CaptureAttemptResult(insertedRowId = rowId, matchedRow = null, matchReason = null)
        }

        // Residual case: the (sourceApp, sender, text, timestamp) unique index rejected an
        // exact repeat that both checks above missed (e.g. timestamp outside the window).
        val byContent = findByContent(sourceApp, sender, text, timestamp)
        return CaptureAttemptResult(insertedRowId = -1L, matchedRow = byContent, matchReason = "exact-content-index")
    }
}
