package com.futurepath.actionbox.search

import com.futurepath.actionbox.data.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSearchTest {

    private fun notification(
        id: Long,
        sourceApp: String = "Gmail",
        sender: String,
        text: String,
        timestamp: Long
    ) = NotificationEntity(
        id = id,
        notificationKey = "key-$id",
        sourceApp = sourceApp,
        sender = sender,
        text = text,
        normalizedText = text.lowercase(),
        timestamp = timestamp,
        capturedAt = timestamp
    )

    @Test
    fun `blank query matches nothing`() {
        val notifications = listOf(notification(1, sender = "Alice", text = "Let's meet Friday", timestamp = 100))
        assertTrue(NotificationSearch.search(notifications, "").isEmpty())
        assertTrue(NotificationSearch.search(notifications, "   ").isEmpty())
    }

    @Test
    fun `matches against message text case-insensitively`() {
        val target = notification(1, sender = "Alice", text = "Please review the budget spreadsheet", timestamp = 100)
        val other = notification(2, sender = "Bob", text = "See you tomorrow", timestamp = 200)
        val results = NotificationSearch.search(listOf(target, other), "BUDGET")
        assertEquals(listOf(target), results)
    }

    @Test
    fun `matches against sender case-insensitively`() {
        val target = notification(1, sender = "Alice Rivera", text = "unrelated text", timestamp = 100)
        val other = notification(2, sender = "Bob", text = "unrelated text", timestamp = 200)
        val results = NotificationSearch.search(listOf(target, other), "rivera")
        assertEquals(listOf(target), results)
    }

    @Test
    fun `matches regardless of source app or category`() {
        val whatsapp = notification(1, sourceApp = "WhatsApp", sender = "Alice", text = "invoice attached", timestamp = 100)
        val gmail = notification(2, sourceApp = "Gmail", sender = "Bob", text = "invoice attached", timestamp = 200)
        val results = NotificationSearch.search(listOf(whatsapp, gmail), "invoice")
        assertEquals(setOf(whatsapp, gmail), results.toSet())
    }

    @Test
    fun `results are ordered most-recent-first`() {
        val oldest = notification(1, sender = "Alice", text = "project update", timestamp = 100)
        val newest = notification(2, sender = "Alice", text = "project update", timestamp = 300)
        val middle = notification(3, sender = "Alice", text = "project update", timestamp = 200)
        val results = NotificationSearch.search(listOf(oldest, newest, middle), "project")
        assertEquals(listOf(newest, middle, oldest), results)
    }

    @Test
    fun `no match returns an empty list`() {
        val notifications = listOf(notification(1, sender = "Alice", text = "Let's meet Friday", timestamp = 100))
        assertTrue(NotificationSearch.search(notifications, "nonexistent").isEmpty())
    }
}
