package com.futurepath.actionbox.reminders

import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.data.effectiveState

/** The computed stats behind the Pro weekly insights digest — see [WeeklyInsightsCalculator]. */
data class WeeklyInsights(
    val hasEnoughHistory: Boolean,
    val totalCaptured: Int,
    val countByCategory: Map<ClassifiedState, Int>,
    val staleWaitingCount: Int
)

/**
 * The stats behind the Pro weekly insights digest ([WeeklyInsightsWorker]) and its matching
 * insights screen (ui/insights/WeeklyInsightsScreen.kt) — pure over an already-loaded
 * notification list and "now", so it's directly unit-testable without Room or WorkManager, same
 * convention as [WaitingTimeframeResolver]/[DigestFormatter].
 */
object WeeklyInsightsCalculator {
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    private const val STALE_WAITING_MS = 5 * 24 * 60 * 60 * 1000L

    private val CATEGORY_LABELS = listOf(
        ClassifiedState.ACTION to "Action",
        ClassifiedState.WAITING to "Waiting",
        ClassifiedState.DEADLINE to "Deadline",
        ClassifiedState.REPLY to "Reply"
    )

    /**
     * [WeeklyInsights.hasEnoughHistory] is false when the earliest captured notification is
     * less than 7 days old — distinct from "zero notifications this week"
     * ([WeeklyInsights.totalCaptured] == 0), which IS a meaningful result: nothing came in that
     * week, as opposed to there simply not being a full week of history to summarize yet.
     */
    fun compute(notifications: List<NotificationEntity>, nowMs: Long): WeeklyInsights {
        val earliest = notifications.minOfOrNull { it.timestamp }
        val hasEnoughHistory = earliest != null && nowMs - earliest >= WEEK_MS

        val windowStart = nowMs - WEEK_MS
        val recent = notifications.filter { it.timestamp >= windowStart }
        val countByCategory = recent.groupingBy { it.effectiveState ?: ClassifiedState.FYI }.eachCount()

        // Unresolved specifically means still active (not swiped handled) — a WAITING item the
        // user already dealt with shouldn't count against them just because it took a while.
        val staleWaitingCount = notifications.count {
            it.effectiveState == ClassifiedState.WAITING &&
                it.handledAt == null &&
                nowMs - it.timestamp >= STALE_WAITING_MS
        }

        return WeeklyInsights(
            hasEnoughHistory = hasEnoughHistory,
            totalCaptured = recent.size,
            countByCategory = countByCategory,
            staleWaitingCount = staleWaitingCount
        )
    }

    /**
     * The notification text — null when [WeeklyInsights.hasEnoughHistory] is false, since
     * [WeeklyInsightsWorker] should simply skip posting rather than send an incomplete digest;
     * the insights screen shows its own "not enough data yet" state instead when opened
     * directly before a week of history exists.
     */
    fun summaryText(insights: WeeklyInsights): String? {
        if (!insights.hasEnoughHistory) return null

        val categoryParts = CATEGORY_LABELS.mapNotNull { (state, label) ->
            val count = insights.countByCategory[state] ?: 0
            if (count == 0) null else "$count $label"
        }
        val categorySummary = if (categoryParts.isEmpty()) "nothing new" else categoryParts.joinToString(", ")

        val staleNote = if (insights.staleWaitingCount > 0) {
            val plural = if (insights.staleWaitingCount == 1) "" else "s"
            " ${insights.staleWaitingCount} Waiting item$plural unresolved after 5+ days."
        } else {
            ""
        }

        return "This week: ${insights.totalCaptured} notifications ($categorySummary).$staleNote"
    }
}
