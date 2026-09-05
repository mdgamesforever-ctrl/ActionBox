package com.futurepath.actionbox.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

data class CaptureOutcome(
    val wasInserted: Boolean,
    // Human-readable explanation of why the insert was ignored, or null if it succeeded.
    val conflictDetail: String?
)

class NotificationRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).notificationDao()
    private val debugEventDao = AppDatabase.getInstance(context).notificationDebugEventDao()

    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    fun observeCapturedCount(): Flow<Int> = dao.observeCount()

    fun observeDebugEvents(): Flow<List<NotificationDebugEvent>> = debugEventDao.observeAll()

    suspend fun logDebugEvent(event: NotificationDebugEvent) {
        debugEventDao.insert(event)
    }

    /**
     * Dedup is enforced by the two unique indices on [NotificationEntity] (see
     * [NotificationDao.insert]) — the insert itself is the atomic, authoritative decision.
     * When it's ignored, this runs a follow-up lookup purely to explain *why* (which
     * constraint matched, and when that row was originally captured), since "it was a
     * duplicate" isn't enough to tell a genuine repeat callback (matched row captured a
     * second ago) apart from stale data from an earlier test run (matched row captured
     * hours ago).
     */
    suspend fun capture(notificationKey: String, sourceApp: String, sender: String, text: String, timestamp: Long, receivedAt: Long): CaptureOutcome {
        val rowId = dao.insert(
            NotificationEntity(
                notificationKey = notificationKey,
                sourceApp = sourceApp,
                sender = sender,
                text = text,
                timestamp = timestamp,
                capturedAt = receivedAt
            )
        )
        if (rowId != -1L) {
            return CaptureOutcome(wasInserted = true, conflictDetail = null)
        }

        val byKey = dao.findByKey(notificationKey)
        val byContent = dao.findByContent(sourceApp, sender, text, timestamp)
        val detail = buildString {
            byKey?.let {
                append("matched existing row id=${it.id} by notificationKey, captured ${ageDescription(receivedAt - it.capturedAt)} ago")
            }
            byContent?.let {
                if (isNotEmpty()) append("; ")
                append("matched existing row id=${it.id} by content+timestamp, captured ${ageDescription(receivedAt - it.capturedAt)} ago")
            }
            if (isEmpty()) append("insert was ignored but no matching row was found")
        }
        return CaptureOutcome(wasInserted = false, conflictDetail = detail)
    }

    private fun ageDescription(ageMs: Long): String = when {
        ageMs < 60_000 -> "%.1fs".format(ageMs / 1000.0)
        ageMs < 3_600_000 -> "%.1fm".format(ageMs / 60_000.0)
        else -> "%.1fh".format(ageMs / 3_600_000.0)
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
