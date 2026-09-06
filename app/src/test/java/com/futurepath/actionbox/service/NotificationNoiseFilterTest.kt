package com.futurepath.actionbox.service

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers issue 13: recurring OS/system widget notifications (download-manager progress/complete
 * toasts, Quick Search Box "at a glance" widgets like weather) should never reach the classifier.
 * A bare `Notification()` is constructible under this project's plain-JVM unit test setup and its
 * public fields read as their Java defaults (category == null), which is sufficient here since
 * shouldCapture's package check doesn't depend on notification content.
 */
class NotificationNoiseFilterTest {

    @Test
    fun `download manager notifications are blocked`() {
        assertFalse(NotificationNoiseFilter.shouldCapture("com.android.providers.downloads", Notification()))
    }

    @Test
    fun `quick search box widget notifications are blocked`() {
        assertFalse(
            NotificationNoiseFilter.shouldCapture("com.google.android.googlequicksearchbox", Notification())
        )
    }

    @Test
    fun `ordinary messaging app notifications are still captured`() {
        assertTrue(NotificationNoiseFilter.shouldCapture("com.whatsapp", Notification()))
    }
}
