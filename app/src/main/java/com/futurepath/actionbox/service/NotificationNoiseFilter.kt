package com.futurepath.actionbox.service

import android.app.Notification

/**
 * Decides whether a posted notification is worth capturing at all. Call UI and OS-utility
 * notifications are never actionable items, so they're dropped before they ever reach Room
 * rather than being stored and filtered out later.
 */
object NotificationNoiseFilter {

    /**
     * Packages whose notifications are always noise, regardless of content or category.
     * These are in-call UI overlays and OS utility toasts (screenshot taken, screen
     * recording, etc.) that will never need classification into an actionable item.
     */
    private val BLOCKED_PACKAGES = setOf(
        // In-call UI overlays (call state, not a message to act on).
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.google.android.dialer",
        "com.android.server.telecom",
        // OS/system utility notifications.
        "com.samsung.android.app.smartcapture", // Samsung screenshot/screen-recording capture
        "com.android.systemui",                  // battery, USB, silent-mode, etc.
        "com.android.providers.downloads",       // OS download-manager progress/complete toasts
        "com.google.android.googlequicksearchbox" // Google app "at a glance" widgets (weather, etc.)
    )

    fun shouldCapture(packageName: String, notification: Notification): Boolean {
        if (packageName in BLOCKED_PACKAGES) {
            return false
        }
        if (notification.category == Notification.CATEGORY_CALL) {
            return false
        }
        return true
    }
}
