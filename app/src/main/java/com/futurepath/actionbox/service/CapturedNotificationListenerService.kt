package com.futurepath.actionbox.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        if (title.isBlank() && text.isBlank()) {
            // Nothing worth capturing (e.g. a silent/progress-only notification).
            return
        }

        val timestamp = sbn.postTime

        serviceScope.launch {
            repository.capture(
                sourceApp = packageName,
                sender = title,
                text = text,
                timestamp = timestamp
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    companion object {
        private const val TAG = "ActionBoxListener"
    }
}
