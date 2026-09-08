package com.futurepath.actionbox.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.futurepath.actionbox.MainActivity
import com.futurepath.actionbox.R

/**
 * Posts the two kinds of notification ActionBox generates ABOUT ITSELF — the daily digest and
 * WAITING follow-up nudges (see [DigestWorker]/[WaitingNudgeWorker]) — as opposed to the
 * notifications it *captures* from other apps. Kept as one object so channel setup and the
 * permission check only happen in one place.
 */
object ReminderNotifications {

    const val DIGEST_CHANNEL_ID = "digest"
    const val WAITING_NUDGE_CHANNEL_ID = "waiting_nudge"

    private const val DIGEST_NOTIFICATION_ID = 1001
    // Nudges share one notification id — a fresh notify() with the same id replaces the
    // previous nudge notification rather than stacking a new one every scan, so a still-
    // unresolved WAITING item doesn't spam multiple separate notifications over time.
    private const val WAITING_NUDGE_NOTIFICATION_ID = 1002

    /** Safe to call repeatedly — createNotificationChannel is a no-op if the channel already
     * exists with the same id. Called once at app start (see ActionBoxApplication). */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(DIGEST_CHANNEL_ID, "Daily digest", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One notification a day summarizing what needs your attention."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(WAITING_NUDGE_CHANNEL_ID, "Waiting follow-ups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders for WAITING items that haven't heard back in a while."
            }
        )
    }

    fun postDigest(context: Context, text: String) {
        post(context, DIGEST_CHANNEL_ID, DIGEST_NOTIFICATION_ID, "ActionBox digest", text)
    }

    fun postWaitingNudge(context: Context, text: String) {
        post(context, WAITING_NUDGE_CHANNEL_ID, WAITING_NUDGE_NOTIFICATION_ID, "Still waiting?", text)
    }

    private fun post(context: Context, channelId: String, notificationId: Int, title: String, text: String) {
        // POST_NOTIFICATIONS is a runtime permission from API 33 onward (requested when the
        // user enables the digest toggle in Settings — see SettingsScreen); on older versions
        // the manifest declaration alone is enough. Checking here too (rather than trusting the
        // Settings-screen request happened) is what keeps this safe to call from a background
        // worker that has no UI to fall back on if the user later revoked the permission from
        // system settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
