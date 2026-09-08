package com.futurepath.actionbox.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The three preset snooze durations offered on the swipe-left gesture (see
 * ui/components/SwipeableNotificationCard.kt). */
enum class SnoozeDuration {
    ONE_HOUR,
    TOMORROW,
    NEXT_WEEK
}

/**
 * Pure calculation of the epoch-millis "snoozed until" timestamp for a preset [SnoozeDuration],
 * kept free of any Android/WorkManager dependency so it's directly JVM-unit-testable — same
 * convention as [WaitingTimeframeResolver]. [com.futurepath.actionbox.data.NotificationRepository.snooze]
 * stores the result of [resolveUntil] on the notification row; [SnoozeWorker] is what later
 * clears it once [System.currentTimeMillis] passes that value.
 */
object SnoozeCalculator {

    // Matches SettingsRepository.DEFAULT_DIGEST_HOUR — an unrelated constant kept separate
    // (rather than shared) so this object stays free of any dependency on the settings/DataStore
    // layer, but chosen to line up with the same "start of a normal day" anchor the digest uses.
    private const val MORNING_HOUR = 9

    fun resolveUntil(duration: SnoozeDuration, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val target = when (duration) {
            SnoozeDuration.ONE_HOUR -> now.plusHours(1)
            // "Tomorrow" morning, even if it's already past MORNING_HOUR today — this is a
            // "come back at a sane time" pick, not literally "24 hours from now".
            SnoozeDuration.TOMORROW -> now.plusDays(1).with(LocalTime.of(MORNING_HOUR, 0))
            SnoozeDuration.NEXT_WEEK -> now.plusWeeks(1).with(LocalTime.of(MORNING_HOUR, 0))
        }
        return target.toInstant().toEpochMilli()
    }

    fun label(duration: SnoozeDuration): String = when (duration) {
        SnoozeDuration.ONE_HOUR -> "1 hour"
        SnoozeDuration.TOMORROW -> "Tomorrow"
        SnoozeDuration.NEXT_WEEK -> "Next week"
    }
}
