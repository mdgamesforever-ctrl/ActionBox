package com.futurepath.actionbox.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationAccessUtils {

    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }
}
