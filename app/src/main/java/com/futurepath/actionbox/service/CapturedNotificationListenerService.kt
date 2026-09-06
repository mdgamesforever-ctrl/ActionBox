package com.futurepath.actionbox.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.futurepath.actionbox.data.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Captures every incoming notification system-wide (raw, unclassified) and stores it
 * in the local Room database. No content leaves the device.
 */
class CapturedNotificationListenerService : NotificationListenerService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: NotificationRepository

    override fun onCreate() {
        super.onCreate()
        repository = NotificationRepository.getInstance(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener disconnected, requesting rebind")
        // The system dropped the binding (e.g. after a crash). Ask to be reconnected
        // rather than waiting for the user to re-toggle access in Settings.
        requestRebind(ComponentName(applicationContext, CapturedNotificationListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val packageName = sbn.packageName
        if (packageName == applicationContext.packageName) {
            // Don't capture our own notifications.
            return
        }

        if (!NotificationNoiseFilter.shouldCapture(packageName, sbn.notification)) {
            // Call UI overlays and OS utility notifications (screenshots, etc.) are never
            // actionable items — drop them before they ever reach Room.
            return
        }

        if (sbn.isOngoing) {
            // Persistent foreground-service status notifications (e.g. Termux's
            // "N session(s)") update repeatedly without representing a new event.
            return
        }

        val content = extractContent(sbn)
        if (content.sender.isBlank() && content.text.isBlank()) {
            // Nothing worth capturing (e.g. a silent/progress-only notification).
            return
        }

        val receivedAt = System.currentTimeMillis()

        serviceScope.launch {
            repository.capture(
                notificationKey = sbn.key,
                sourceApp = packageName,
                sender = content.sender,
                text = content.text,
                timestamp = content.timestamp,
                receivedAt = receivedAt
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    /**
     * For MessagingStyle notifications (WhatsApp, SMS, etc.), the top-level EXTRA_TEXT/
     * EXTRA_BIG_TEXT is a compatibility summary field that isn't guaranteed to reflect the
     * newest individual message. Reading the actual message list instead gives the latest
     * message's own text and timestamp.
     */
    private fun extractContent(sbn: StatusBarNotification): ExtractedContent {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        val messages = messagingStyle?.messages.orEmpty()

        // Select by max timestamp rather than trusting list position (first/last) — this
        // is correct regardless of which order the underlying platform/OEM happens to
        // return the list in.
        val latestMessage = messages.maxByOrNull { it.timestamp }

        return if (latestMessage != null) {
            ExtractedContent(
                sender = title,
                text = latestMessage.text?.toString().orEmpty(),
                timestamp = latestMessage.timestamp
            )
        } else {
            val rawExtraText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val rawExtraBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ExtractedContent(
                sender = title,
                text = (rawExtraBigText ?: rawExtraText).orEmpty(),
                timestamp = sbn.postTime
            )
        }
    }

    private data class ExtractedContent(
        val sender: String,
        val text: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "ActionBoxListener"
    }
}
