package com.futurepath.actionbox.reminders

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SnoozeCalculatorTest {

    // Wednesday, Jan 10 2024, 10:00:00 UTC — a fixed reference point so date-math assertions
    // are deterministic regardless of when this test actually runs.
    private val zone = ZoneId.of("UTC")
    private val wednesdayMorning = ZonedDateTime.of(2024, 1, 10, 10, 0, 0, 0, zone)
    private val nowMs = wednesdayMorning.toInstant().toEpochMilli()

    @Test
    fun `one hour resolves to exactly one hour later`() {
        val until = SnoozeCalculator.resolveUntil(SnoozeDuration.ONE_HOUR, nowMs, zone)
        assertEquals(wednesdayMorning.plusHours(1).toInstant().toEpochMilli(), until)
    }

    @Test
    fun `tomorrow resolves to 9am the following day`() {
        val until = SnoozeCalculator.resolveUntil(SnoozeDuration.TOMORROW, nowMs, zone)
        val expected = wednesdayMorning.toLocalDate().plusDays(1).atTime(9, 0).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), until)
    }

    @Test
    fun `tomorrow still resolves to the next day's morning even if snoozed after 9am today`() {
        val lateAfternoon = wednesdayMorning.withHour(16)
        val until = SnoozeCalculator.resolveUntil(SnoozeDuration.TOMORROW, lateAfternoon.toInstant().toEpochMilli(), zone)
        val expected = lateAfternoon.toLocalDate().plusDays(1).atTime(9, 0).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), until)
    }

    @Test
    fun `next week resolves to 9am seven days later`() {
        val until = SnoozeCalculator.resolveUntil(SnoozeDuration.NEXT_WEEK, nowMs, zone)
        val expected = wednesdayMorning.toLocalDate().plusWeeks(1).atTime(9, 0).atZone(zone)
        assertEquals(expected.toInstant().toEpochMilli(), until)
    }

    @Test
    fun `labels match the requested preset copy`() {
        assertEquals("1 hour", SnoozeCalculator.label(SnoozeDuration.ONE_HOUR))
        assertEquals("Tomorrow", SnoozeCalculator.label(SnoozeDuration.TOMORROW))
        assertEquals("Next week", SnoozeCalculator.label(SnoozeDuration.NEXT_WEEK))
    }
}
