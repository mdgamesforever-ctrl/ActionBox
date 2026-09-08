package com.futurepath.actionbox.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.futurepath.actionbox.BuildConfig
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.data.DigestTime
import com.futurepath.actionbox.data.NotificationRepository
import java.util.Locale

/**
 * App-wide settings — plan/subscription status, the Pro-gated correction-learning toggle, and
 * the reminder system (daily digest + WAITING follow-up nudges). All backed by
 * [com.futurepath.actionbox.data.SettingsRepository] via the view model, so a toggle here takes
 * effect immediately rather than being a local UI-only flag.
 */
@Composable
fun SettingsScreen(
    isPro: Boolean,
    onUpgradeClick: () -> Unit,
    onDebugProOverrideChange: (Boolean) -> Unit,
    correctionLearningEnabled: Boolean,
    onCorrectionLearningChange: (Boolean) -> Unit,
    digestsEnabled: Boolean,
    onDigestsEnabledChange: (Boolean) -> Unit,
    digestTime: DigestTime,
    onDigestTimeChange: (DigestTime) -> Unit,
    onViewCrashLogClick: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Plan")
        if (isPro) {
            Text(
                text = "✓ Pro — unlimited notification history, no ads, smart corrections enabled.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { openManageSubscription(context) }) {
                Text("Manage subscription")
            }
        } else {
            Text(
                text = "Free plan: notifications are kept for ${NotificationRepository.FREE_RETENTION_DAYS} " +
                    "days, ads are shown, and smart corrections are off. Pro removes all three limits.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onUpgradeClick, modifier = Modifier.fillMaxWidth()) {
                Text("Upgrade to Pro")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Smart corrections")
        Text(
            text = "When you correct a notification's category, ActionBox remembers the pattern " +
                "(sender, app, or exact phrasing) and uses it to classify similar notifications " +
                "going forward. A Pro feature — your on/off preference is kept even while on the " +
                "Free plan and picks back up as soon as you upgrade.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggleRow(
            title = "Learn from my corrections",
            subtitle = if (!isPro) "Requires Pro — tap to upgrade" else null,
            // Reflects what's actually ACTIVE (see NotificationRepository.learningBoostsFor,
            // which requires both isPro and this preference) rather than just the raw stored
            // preference, so a Free user never sees this toggle "on" while it does nothing.
            checked = isPro && correctionLearningEnabled,
            onCheckedChange = { wantsOn ->
                if (!isPro) onUpgradeClick() else onCorrectionLearningChange(wantsOn)
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Reminders")
        Text(
            text = "Get one notification a day summarizing what needs your attention (e.g. " +
                "\"3 Actions, 2 Replies, 1 Waiting need your attention\"), plus a follow-up " +
                "nudge if something you're WAITING on hasn't heard back within its implied " +
                "timeframe.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        DigestToggleRow(digestsEnabled = digestsEnabled, onDigestsEnabledChange = onDigestsEnabledChange)
        if (digestsEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            DigestTimeRow(time = digestTime, onTimeChange = onDigestTimeChange)
        }

        // Debug-only escape hatch for our own testing, since there's no Play Console listing
        // reachable from a dev sandbox to actually purchase against (see BillingRepository's
        // doc) — BuildConfig.DEBUG is a compile-time constant per build type, so R8 dead-code-
        // eliminates this entire block (including the toggle and its callback) out of release
        // builds rather than just hiding it at runtime. Never shown to a real user.
        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("Debug tools")
            Text(
                text = "For our own testing only — bypasses Google Play Billing entirely rather than " +
                    "making a real purchase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            SettingToggleRow(
                title = "Simulate Pro",
                subtitle = "No real purchase — for local testing only.",
                checked = isPro,
                onCheckedChange = onDebugProOverrideChange
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onViewCrashLogClick) {
                Text("View last crash log")
            }
        }
    }
}

private fun openManageSubscription(context: Context) {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
            "?sku=${BillingRepository.PRO_SUBSCRIPTION_PRODUCT_ID}&package=${context.packageName}"
    )
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

/**
 * Wraps the digest toggle with the API 33+ POST_NOTIFICATIONS runtime-permission request:
 * turning it ON when that permission isn't already granted asks for it first, and only reports
 * the toggle as enabled if the user actually grants it — leaving it visibly OFF rather than
 * silently "on" with no notifications ever appearing. Turning it OFF never needs the
 * permission, so that path skips straight to [onDigestsEnabledChange].
 */
@Composable
private fun DigestToggleRow(digestsEnabled: Boolean, onDigestsEnabledChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onDigestsEnabledChange(granted) }

    SettingToggleRow(
        title = "Daily digest & waiting nudges",
        subtitle = null,
        checked = digestsEnabled,
        onCheckedChange = { wantsEnabled ->
            if (!wantsEnabled) {
                onDigestsEnabledChange(false)
                return@SettingToggleRow
            }
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onDigestsEnabledChange(true)
            }
        }
    )
}

@Composable
private fun DigestTimeRow(time: DigestTime, onTimeChange: (DigestTime) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Digest time", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(formatDigestTime(time))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DIGEST_TIME_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(formatDigestTime(option)) },
                        onClick = {
                            expanded = false
                            onTimeChange(option)
                        }
                    )
                }
            }
        }
    }
}

// Half-hour granularity is plenty for "roughly when in the morning/day should this fire" —
// full-precision minute selection isn't worth the extra UI for this setting.
private val DIGEST_TIME_OPTIONS: List<DigestTime> = (0 until 24).flatMap { hour ->
    listOf(DigestTime(hour, 0), DigestTime(hour, 30))
}

private fun formatDigestTime(time: DigestTime): String {
    val amPm = if (time.hour < 12) "AM" else "PM"
    val hour12 = time.hour % 12
    return String.format(Locale.US, "%d:%02d %s", if (hour12 == 0) 12 else hour12, time.minute, amPm)
}

@Composable
private fun SectionHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SettingToggleRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
