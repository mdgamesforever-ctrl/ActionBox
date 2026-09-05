package com.futurepath.actionbox.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/**
 * NotificationListenerService is not always automatically rebound by the system after
 * a reboot or app update. Nudging a rebind here ensures capture resumes without the
 * user having to manually re-toggle notification access in Settings.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        NotificationListenerService.requestRebind(
            ComponentName(context, CapturedNotificationListenerService::class.java)
        )
    }
}
