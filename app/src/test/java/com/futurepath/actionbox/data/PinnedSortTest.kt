package com.futurepath.actionbox.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedSortTest {
    private fun n(id: Long, ts: Long, pinnedAt: Long? = null) = NotificationEntity(
        id = id, notificationKey = "k$id", sourceApp = "a", sender = "s", text = "t",
        normalizedText = "t", timestamp = ts, capturedAt = ts, pinnedAt = pinnedAt
    )

    @Test
    fun `pinned first, most recently pinned on top, rest newest first`() {
        val items = listOf(n(1, 100), n(2, 300), n(3, 50, pinnedAt = 10), n(4, 200, pinnedAt = 20), n(5, 400))
        assertEquals(listOf(4L, 3L, 5L, 2L, 1L), items.sortedPinnedFirst().map { it.id })
    }

    @Test
    fun `no pins keeps plain newest-first order`() {
        val items = listOf(n(1, 100), n(2, 300), n(3, 200))
        assertEquals(listOf(2L, 3L, 1L), items.sortedPinnedFirst().map { it.id })
    }
}
