package com.futurepath.actionbox.reminders

import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WeeklyInsightsCalculatorTest {

    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2024, 1, 15, 18, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun notification(
        id: Long,
        state: ClassifiedState,
        timestamp: Long,
        handledAt: Long? = null
    ) = NotificationEntity(
        id = id,
        notificationKey = "key-$id",
        sourceApp = "Gmail",
        sender = "Alice",
        text = "text $id",
        normalizedText = "text $id",
        timestamp = timestamp,
        capturedAt = timestamp,
        classifiedState = state,
        handledAt = handledAt
    )

    @Test
    fun `less than a week of history reports not enough data`() {
        val notifications = listOf(notification(1, ClassifiedState.ACTION, now - daysMs(2)))
        val insights = WeeklyInsightsCalculator.compute(notifications, now)
        assertFalse(insights.hasEnoughHistory)
        assertNull(WeeklyInsightsCalculator.summaryText(insights))
    }

    @Test
    fun `empty history also reports not enough data rather than throwing`() {
        val insights = WeeklyInsightsCalculator.compute(emptyList(), now)
        assertFalse(insights.hasEnoughHistory)
        assertNull(WeeklyInsightsCalculator.summaryText(insights))
    }

    @Test
    fun `a week or more of history counts per category within the last 7 days`() {
        val notifications = listOf(
            notification(1, ClassifiedState.ACTION, now - daysMs(10)), // outside the window
            notification(2, ClassifiedState.ACTION, now - daysMs(1)),
            notification(3, ClassifiedState.WAITING, now - daysMs(2)),
            notification(4, ClassifiedState.WAITING, now - daysMs(3)),
            notification(5, ClassifiedState.REPLY, now - daysMs(4))
        )
        val insights = WeeklyInsightsCalculator.compute(notifications, now)

        assertTrue(insights.hasEnoughHistory)
        assertEquals(4, insights.totalCaptured)
        assertEquals(1, insights.countByCategory[ClassifiedState.ACTION])
        assertEquals(2, insights.countByCategory[ClassifiedState.WAITING])
        assertEquals(1, insights.countByCategory[ClassifiedState.REPLY])

        val text = WeeklyInsightsCalculator.summaryText(insights)
        assertTrue(text!!.contains("4 notifications"))
        assertTrue(text.contains("1 Action"))
        assertTrue(text.contains("2 Waiting"))
    }

    @Test
    fun `waiting items unresolved 5+ days are counted stale regardless of the weekly window`() {
        val notifications = listOf(
            notification(1, ClassifiedState.WAITING, now - daysMs(20)), // old but still stale/active
            notification(2, ClassifiedState.WAITING, now - daysMs(6)),
            notification(3, ClassifiedState.WAITING, now - daysMs(3)) // not stale yet
        )
        val insights = WeeklyInsightsCalculator.compute(notifications, now)
        assertEquals(2, insights.staleWaitingCount)
        assertTrue(WeeklyInsightsCalculator.summaryText(insights)!!.contains("2 Waiting items unresolved after 5+ days"))
    }

    @Test
    fun `a handled waiting item does not count as stale even if old`() {
        val notifications = listOf(
            notification(1, ClassifiedState.WAITING, now - daysMs(10), handledAt = now - daysMs(1))
        )
        val insights = WeeklyInsightsCalculator.compute(notifications, now)
        assertEquals(0, insights.staleWaitingCount)
    }

    @Test
    fun `zero notifications this week is a valid distinct result from not enough history`() {
        val notifications = listOf(notification(1, ClassifiedState.ACTION, now - daysMs(20)))
        val insights = WeeklyInsightsCalculator.compute(notifications, now)
        assertTrue(insights.hasEnoughHistory)
        assertEquals(0, insights.totalCaptured)
        assertTrue(WeeklyInsightsCalculator.summaryText(insights)!!.contains("0 notifications (nothing new)"))
    }

    private fun daysMs(days: Int): Long = days * 24 * 60 * 60 * 1000L
}
