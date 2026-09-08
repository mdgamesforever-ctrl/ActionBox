package com.futurepath.actionbox.reminders

import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Resolves a WAITING notification's "implied timeframe" — the point past which a commitment
 * like "I'll send it by Friday" or "give me a sec" is worth a follow-up nudge — from the same
 * `extractedDate` token [com.futurepath.actionbox.classification.NotificationClassifier]
 * already pulls out of the message text for display. That extraction is always a single atomic
 * token (a day name, a relative phrase, a bare time, or a date — never a combined phrase, since
 * it comes from a single regex `find()` call), which is what keeps this resolver's job simple:
 * parse one token, or fall back.
 *
 * Deliberately conservative: a bare month name with no day number, or any token this doesn't
 * recognize at all, falls back to [DEFAULT_WINDOW_MS] rather than guessing at a date.
 */
object WaitingTimeframeResolver {

    /**
     * Casual commitments with no resolvable date at all ("on it", "give me a sec") are
     * implicitly "soon" — two days is what "soon" is worth waiting past before a follow-up
     * nudge is warranted.
     */
    val DEFAULT_WINDOW_MS: Long = Duration.ofDays(2).toMillis()

    fun isOverdue(
        extractedDate: String?,
        messageTimeMs: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = nowMs >= impliedDeadlineMs(extractedDate, messageTimeMs, zone)

    /** The epoch millis past which [extractedDate] (as understood relative to [messageTimeMs])
     * counts as overdue. Exposed separately from [isOverdue] so callers/tests can inspect it
     * directly rather than only a boolean. */
    fun impliedDeadlineMs(extractedDate: String?, messageTimeMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (extractedDate.isNullOrBlank()) return messageTimeMs + DEFAULT_WINDOW_MS
        val messageTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(messageTimeMs), zone)
        val resolved = resolve(extractedDate.lowercase(Locale.US).trim(), messageTime)
        return resolved?.toInstant()?.toEpochMilli() ?: (messageTimeMs + DEFAULT_WINDOW_MS)
    }

    private fun resolve(token: String, from: ZonedDateTime): ZonedDateTime? {
        DAY_NAMES[token]?.let { return nextOccurrenceOfDay(from, it).with(LocalTime.MAX) }
        return when (token) {
            "today", "tonight", "midnight" -> from.with(LocalTime.MAX)
            "tomorrow" -> from.plusDays(1).with(LocalTime.MAX)
            "noon" -> nextOccurrenceOfTime(from, LocalTime.NOON)
            "next week" -> from.plusWeeks(1).with(LocalTime.MAX)
            "end of month", "end of the month" -> endOfMonth(from)
            // "End of week" has no universal definition — Saturday is treated as the last day
            // of the (Sun-Sat) week here, a reasonable default absent any stronger signal.
            "end of week", "end of the week" -> nextOccurrenceOfDay(from, DayOfWeek.SATURDAY).with(LocalTime.MAX)
            else -> resolveTimeOfDay(token, from) ?: resolveOrdinalDay(token, from) ?: resolveSlashDate(token, from)
        }
    }

    private val DAY_NAMES = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )

    /** The next date (today included) whose day-of-week is [day] — "I'll send it by Friday"
     * said ON a Friday means end of that same day, not a week later. */
    private fun nextOccurrenceOfDay(from: ZonedDateTime, day: DayOfWeek): ZonedDateTime {
        var d = from
        while (d.dayOfWeek != day) d = d.plusDays(1)
        return d
    }

    private fun nextOccurrenceOfTime(from: ZonedDateTime, time: LocalTime): ZonedDateTime {
        val todayAtTime = from.toLocalDate().atTime(time).atZone(from.zone)
        return if (todayAtTime.isAfter(from)) todayAtTime else todayAtTime.plusDays(1)
    }

    private fun endOfMonth(from: ZonedDateTime): ZonedDateTime {
        val lastDay = from.toLocalDate().withDayOfMonth(from.toLocalDate().lengthOfMonth())
        return lastDay.atStartOfDay(from.zone).with(LocalTime.MAX)
    }

    private val TIME_OF_DAY = Regex("^(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)$")

    private fun resolveTimeOfDay(token: String, from: ZonedDateTime): ZonedDateTime? {
        val (hourStr, minuteStr, meridiem) = TIME_OF_DAY.find(token)?.destructured ?: return null
        val hour12 = hourStr.toIntOrNull() ?: return null
        if (hour12 !in 1..12) return null
        val hour = (hour12 % 12) + if (meridiem == "pm") 12 else 0
        val minute = minuteStr.toIntOrNull() ?: 0
        return nextOccurrenceOfTime(from, LocalTime.of(hour, minute))
    }

    private val ORDINAL_DAY = Regex("^(\\d{1,2})(?:st|nd|rd|th)$")

    private fun resolveOrdinalDay(token: String, from: ZonedDateTime): ZonedDateTime? {
        val day = ORDINAL_DAY.find(token)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val today = from.toLocalDate()
        if (day < 1 || day > today.lengthOfMonth()) return null
        val candidate = today.withDayOfMonth(day)
        return try {
            val target = if (!candidate.isBefore(today)) candidate else candidate.plusMonths(1).withDayOfMonth(day)
            target.atStartOfDay(from.zone).with(LocalTime.MAX)
        } catch (e: DateTimeException) {
            null
        }
    }

    private val SLASH_DATE = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$")

    private fun resolveSlashDate(token: String, from: ZonedDateTime): ZonedDateTime? {
        val (monthStr, dayStr, yearStr) = SLASH_DATE.find(token)?.destructured ?: return null
        val month = monthStr.toIntOrNull() ?: return null
        val day = dayStr.toIntOrNull() ?: return null
        val year = yearStr.toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: from.year
        return try {
            LocalDate.of(year, month, day).atStartOfDay(from.zone).with(LocalTime.MAX)
        } catch (e: DateTimeException) {
            null
        }
    }
}
