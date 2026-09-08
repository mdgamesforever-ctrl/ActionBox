package com.futurepath.actionbox.reminders

import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigestFormatterTest {

    private var nextId = 1L

    private fun notification(classifiedState: ClassifiedState?, correctedState: ClassifiedState? = null) =
        NotificationEntity(
            id = nextId++,
            notificationKey = "key",
            sourceApp = "com.example.app",
            sender = "Sender",
            text = "text",
            normalizedText = "text",
            timestamp = 0L,
            capturedAt = 0L,
            classifiedState = classifiedState,
            correctedState = correctedState
        )

    @Test
    fun `no notifications produces no digest`() {
        assertNull(DigestFormatter.format(emptyList()))
    }

    @Test
    fun `only FYI and NOISE items produces no digest`() {
        val notifications = listOf(notification(ClassifiedState.FYI), notification(ClassifiedState.NOISE))
        assertNull(DigestFormatter.format(notifications))
    }

    @Test
    fun `counts each actionable category and skips empty ones`() {
        val notifications = listOf(
            notification(ClassifiedState.ACTION),
            notification(ClassifiedState.ACTION),
            notification(ClassifiedState.ACTION),
            notification(ClassifiedState.WAITING),
            notification(ClassifiedState.REPLY),
            notification(ClassifiedState.REPLY),
            notification(ClassifiedState.FYI)
        )
        assertEquals("3 Actions, 1 Waiting, 2 Replies need your attention", DigestFormatter.format(notifications))
    }

    @Test
    fun `a single overall item uses singular grammar`() {
        val notifications = listOf(notification(ClassifiedState.DEADLINE))
        assertEquals("1 Deadline needs your attention", DigestFormatter.format(notifications))
    }

    @Test
    fun `waiting label does not pluralize`() {
        val notifications = listOf(notification(ClassifiedState.WAITING), notification(ClassifiedState.WAITING))
        assertEquals("2 Waiting need your attention", DigestFormatter.format(notifications))
    }

    @Test
    fun `a user correction overrides the classifier's own category`() {
        val notifications = listOf(notification(classifiedState = ClassifiedState.FYI, correctedState = ClassifiedState.ACTION))
        assertEquals("1 Action needs your attention", DigestFormatter.format(notifications))
    }
}
