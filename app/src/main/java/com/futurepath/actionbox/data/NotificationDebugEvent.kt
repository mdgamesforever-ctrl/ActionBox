package com.futurepath.actionbox.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per onNotificationPosted call, regardless of outcome — including calls that
 * were filtered as noise, deduped as a repeat, or skipped as blank. Unlike
 * [NotificationEntity] (which only holds genuine captured items for eventual
 * classification), this is a pure diagnostic trail so a dropped or duplicated message can
 * be inspected on-device without adb/logcat.
 */
@Entity(tableName = "notification_debug_events")
data class NotificationDebugEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val receivedAt: Long,
    val notificationKey: String,
    val sourceApp: String,
    val rawExtraText: String?,
    val rawExtraBigText: String?,
    val extractionSource: String,
    val messagingStyleDump: String,
    val resolvedSender: String,
    val resolvedText: String,
    val resolvedTimestamp: Long,
    val outcome: String,
    // Populated only when outcome is "duplicate_ignored": which constraint matched, which
    // existing row it matched, and how long ago that row was originally captured — so a
    // duplicate can be told apart from "blocked by stale data from an earlier test run."
    val conflictDetail: String?
)
