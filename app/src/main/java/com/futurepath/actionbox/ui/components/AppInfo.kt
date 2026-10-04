package com.futurepath.actionbox.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.futurepath.actionbox.R
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/** What a card header shows for a source package: its human name, icon, and whether it can be opened. */
class AppInfo(val label: String, val icon: ImageBitmap?, val canOpen: Boolean)

private val cache = ConcurrentHashMap<String, AppInfo>()
private val missing = AppInfo("", null, false)

/**
 * Resolves [packageName] (what NotificationEntity.sourceApp stores) to its installed app's label
 * and icon. Null when it isn't an installed, launcher-visible package (uninstalled app, demo
 * data, a non-package value) — callers fall back to the raw string. Cached per process: icons
 * are rasterized once at 96px, plenty for the 24dp header slot.
 */
@Composable
fun rememberAppInfo(packageName: String): AppInfo? {
    val context = LocalContext.current
    return remember(packageName) {
        cache.getOrPut(packageName) { resolve(context, packageName) }.takeIf { it !== missing }
    }
}

private fun resolve(context: Context, packageName: String): AppInfo = try {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(packageName, 0)
    AppInfo(
        label = pm.getApplicationLabel(info).toString(),
        icon = pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap(),
        canOpen = pm.getLaunchIntentForPackage(packageName) != null
    )
} catch (e: Exception) {
    missing
}

/**
 * Opens the app a notification came from. This is app-level only: a notification's own tap
 * target (the specific chat) is a PendingIntent that can't be stored, so it's gone once the
 * original notification leaves the shade.
 */
fun openSourceApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        if (intent == null) throw IllegalStateException("not launchable")
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, R.string.toast_cannot_open_app, Toast.LENGTH_SHORT).show()
    }
}
