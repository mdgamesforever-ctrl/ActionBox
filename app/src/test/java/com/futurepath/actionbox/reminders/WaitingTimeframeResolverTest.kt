package com.futurepath.actionbox.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WaitingTimeframeResolverTest {

    // Wednesday, Jan 10 2024, 10:00:00 UTC — a fixed, known day-of-week so day-name resolution
    // is deterministic regardless of when this test actually runs.
    private val zone = ZoneId.of("UTC")
    private val wednesdayMorning = ZonedDateTime.of(2024, 1, 10, 10, 0, 0, 0, zone)
    private val messageTimeMs = wednesdayMorning.toInstant().toEpochMilli()

    @Test
    fun `no extracted date falls back to the default two-day window`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs(null, messageTimeMs, zone)
        assertEquals(messageTimeMs + WaitingTimeframeResolver.DEFAULT_WINDOW_MS, deadline)
    }

    @Test
    fun `blank extracted date also falls back to the default window`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("  ", messageTimeMs, zone)
        assertEquals(messageTimeMs + WaitingTimeframeResolver.DEFAULT_WINDOW_MS, deadline)
    }

    @Test
    fun `an unrecognized token falls back to the default window rather than guessing`() {
        // A bare month name with no day number ("see you in december") is too ambiguous to
        // resolve to a specific date.
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("december", messageTimeMs, zone)
        assertEquals(messageTimeMs + WaitingTimeframeResolver.DEFAULT_WINDOW_MS, deadline)
    }

    @Test
    fun `today resolves to the end of the message's own day`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("today", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().atTime(23, 59, 59, 999_000_000).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `tomorrow resolves to the end of the following day`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("tomorrow", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().plusDays(1).atTime(23, 59, 59, 999_000_000).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `the same day name as today resolves to end of today, not a week later`() {
        // messageTime is itself a Wednesday.
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("wednesday", messageTimeMs, zone)
        val todayDeadline = WaitingTimeframeResolver.impliedDeadlineMs("today", messageTimeMs, zone)
        assertEquals(todayDeadline, deadline)
    }

    @Test
    fun `a future day name resolves to that day's end, not the next week's occurrence`() {
        // Wednesday -> Friday is 2 days out.
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("friday", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().plusDays(2).atTime(23, 59, 59, 999_000_000).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `a day name earlier in the week wraps to next week rather than going backward`() {
        // Wednesday -> Monday is 5 days forward (never a negative/past date).
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("monday", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().plusDays(5).atTime(23, 59, 59, 999_000_000).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `a bare time later today resolves to that exact time today`() {
        // messageTime is 10am; 5pm hasn't happened yet today.
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("5pm", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().atTime(17, 0).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `a bare time already passed today rolls over to that time tomorrow`() {
        // messageTime is 10am; 9am has already passed today.
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("9am", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().plusDays(1).atTime(9, 0).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `noon resolves like any other bare time`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("noon", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().atTime(12, 0).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `an ordinal day later this month resolves within this month`() {
        // messageTime is Jan 10; "the 15th" is still ahead this month.
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("15th", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().withDayOfMonth(15).atTime(23, 59, 59, 999_000_000).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `an ordinal day already passed this month rolls to next month`() {
        // messageTime is Jan 10; "the 1st" already happened this month.
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("1st", messageTimeMs, zone)
        val expected = wednesdayMorning.toLocalDate().plusMonths(1).withDayOfMonth(1)
            .atTime(23, 59, 59, 999_000_000).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `a slash date resolves to that calendar date`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("3/1", messageTimeMs, zone)
        val expected = ZonedDateTime.of(2024, 3, 1, 23, 59, 59, 999_000_000, zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `end of month resolves to the last day of the message's month`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("end of month", messageTimeMs, zone)
        val expected = ZonedDateTime.of(2024, 1, 31, 23, 59, 59, 999_000_000, zone)
        assertEquals(expected.toInstant().toEpochMilli(), deadline)
    }

    @Test
    fun `isOverdue is false before the implied deadline and true after it`() {
        val deadline = WaitingTimeframeResolver.impliedDeadlineMs("tomorrow", messageTimeMs, zone)
        assertFalse(WaitingTimeframeResolver.isOverdue("tomorrow", messageTimeMs, deadline - 1, zone))
        assertTrue(WaitingTimeframeResolver.isOverdue("tomorrow", messageTimeMs, deadline + 1, zone))
    }
}
