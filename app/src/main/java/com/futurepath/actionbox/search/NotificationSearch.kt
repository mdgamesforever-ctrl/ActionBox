package com.futurepath.actionbox.search

import com.futurepath.actionbox.data.NotificationEntity

/**
 * Cross-app keyword search over an already-loaded list of notifications — deliberately in-memory
 * rather than a Room `LIKE` query, for two reasons: it only ever searches what
 * [com.futurepath.actionbox.viewmodel.NotificationViewModel.notifications] has already loaded,
 * which automatically respects whatever retention window applies (14 days for Free, unlimited
 * for Pro — see [com.futurepath.actionbox.data.NotificationRepository.FREE_RETENTION_DAYS])
 * without this needing to know anything about that policy itself; and it keeps the matching/
 * ranking logic a plain, trivially JVM-unit-testable function rather than needing an
 * instrumented/Robolectric Room test.
 */
object NotificationSearch {

    /**
     * Matches [query] against a notification's [NotificationEntity.text] or
     * [NotificationEntity.sender], case-insensitively, regardless of category or source app.
     * Results are most-recent-first. A blank query matches nothing — there's no "browse
     * everything" mode here, only search.
     */
    fun search(notifications: List<NotificationEntity>, query: String): List<NotificationEntity> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return notifications
            .filter { it.text.contains(trimmed, ignoreCase = true) || it.sender.contains(trimmed, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
    }
}
