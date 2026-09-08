package com.futurepath.actionbox.reminders

import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.data.effectiveState
import com.futurepath.actionbox.ui.inbox.InboxTab

/**
 * Builds the daily digest's summary line ("3 Actions, 1 Waiting need your attention") from the
 * current notification list — counts per [InboxTab], skipping [InboxTab.OTHER] (FYI/NOISE)
 * since those are the de-emphasized categories in the inbox UI too, and skipping any tab
 * that's currently empty rather than reporting "0 Replies".
 */
object DigestFormatter {

    // WAITING is deliberately invariant ("1 Waiting", "3 Waiting") rather than pluralizing —
    // it reads as a status/gerund noun, not a countable one, the same way the user's own
    // example phrasing ("1 Waiting need your attention") uses it.
    private val LABELS: Map<InboxTab, Pair<String, String>> = mapOf(
        InboxTab.ACTION to ("Action" to "Actions"),
        InboxTab.WAITING to ("Waiting" to "Waiting"),
        InboxTab.DEADLINE to ("Deadline" to "Deadlines"),
        InboxTab.REPLY to ("Reply" to "Replies")
    )

    // Tab order for the digest sentence — matches the bottom-nav tab order (InboxTab.entries)
    // for consistency with the rest of the UI rather than any particular urgency ranking.
    private val DIGEST_TABS = InboxTab.entries.filter { it != InboxTab.OTHER }

    /** Null when every actionable tab is empty — callers should skip posting a notification
     * in that case rather than send an empty digest. */
    fun format(notifications: List<NotificationEntity>): String? {
        val counts = DIGEST_TABS.associateWith { tab ->
            notifications.count { it.effectiveState in tab.states }
        }
        val parts = DIGEST_TABS.mapNotNull { tab ->
            val count = counts.getValue(tab)
            if (count == 0) return@mapNotNull null
            val (singular, plural) = LABELS.getValue(tab)
            "$count ${if (count == 1) singular else plural}"
        }
        if (parts.isEmpty()) return null

        val verb = if (counts.values.sum() == 1) "needs" else "need"
        return "${parts.joinToString(", ")} $verb your attention"
    }
}
