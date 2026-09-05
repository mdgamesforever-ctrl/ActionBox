package com.futurepath.actionbox.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.futurepath.actionbox.data.NotificationDebugEvent
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
            // Don't capture or log our own notifications.
            return
        }

        val receivedAt = System.currentTimeMillis()
        val content = extractContent(sbn)

        // Every path below still logs a NotificationDebugEvent with the raw data and the
        // reason nothing was captured, so a filtered/deduped/dropped notification is
        // visible in the on-device debug feed exactly like a captured one.
        val outcome: String? = when {
            !NotificationNoiseFilter.shouldCapture(packageName, sbn.notification) -> "filtered_noise"
            sbn.isOngoing -> "filtered_ongoing"
            content.sender.isBlank() && content.text.isBlank() -> "blank_skipped"
            else -> null // proceed to the capture attempt below
        }

        serviceScope.launch {
            val finalOutcome: String
            val conflictDetail: String?

            if (outcome != null) {
                finalOutcome = outcome
                conflictDetail = null
            } else {
                val result = repository.capture(
                    notificationKey = sbn.key,
                    sourceApp = packageName,
                    sender = content.sender,
                    text = content.text,
                    timestamp = content.timestamp,
                    receivedAt = receivedAt
                )
                finalOutcome = if (result.wasInserted) "captured" else "duplicate_ignored"
                conflictDetail = result.conflictDetail
            }

            repository.logDebugEvent(
                NotificationDebugEvent(
                    receivedAt = receivedAt,
                    notificationKey = sbn.key,
                    sourceApp = packageName,
                    rawExtraText = content.rawExtraText,
                    rawExtraBigText = content.rawExtraBigText,
                    extractionSource = content.source,
                    messagingStyleDump = content.messagingStyleDump,
                    resolvedSender = content.sender,
                    resolvedText = content.text,
                    resolvedTimestamp = content.timestamp,
                    outcome = finalOutcome,
                    conflictDetail = conflictDetail
                )
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
     * message's own text and timestamp. Also returns the raw fields and the full message
     * list dump so they can be inspected on-device via the debug feed.
     */
    private fun extractContent(sbn: StatusBarNotification): ExtractedContent {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val rawExtraText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val rawExtraBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        val messages = messagingStyle?.messages.orEmpty()
        val messagingStyleDump = if (messages.isEmpty()) {
            "(none)"
        } else {
            messages.mapIndexed { index, message -> "[$index] t=${message.timestamp} \"${message.text}\"" }
                .joinToString("\n")
        }

        // Select by max timestamp rather than trusting list position (first/last) — this
        // is correct regardless of which order the underlying platform/OEM happens to
        // return the list in.
        val latestMessage = messages.maxByOrNull { it.timestamp }

        return if (latestMessage != null) {
            ExtractedContent(
                sender = title,
                text = latestMessage.text?.toString().orEmpty(),
                timestamp = latestMessage.timestamp,
                source = "messagingStyle",
                rawExtraText = rawExtraText,
                rawExtraBigText = rawExtraBigText,
                messagingStyleDump = messagingStyleDump
            )
        } else {
            ExtractedContent(
                sender = title,
                text = (rawExtraBigText ?: rawExtraText).orEmpty(),
                timestamp = sbn.postTime,
                source = "topLevelExtras",
                rawExtraText = rawExtraText,
                rawExtraBigText = rawExtraBigText,
                messagingStyleDump = messagingStyleDump
            )
        }
    }

    private data class ExtractedContent(
        val sender: String,
        val text: String,
        val timestamp: Long,
        val source: String,
        val rawExtraText: String?,
        val rawExtraBigText: String?,
        val messagingStyleDump: String
    )

    companion object {
        private const val TAG = "ActionBoxListener"
    }
}
