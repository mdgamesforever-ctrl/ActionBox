package com.futurepath.actionbox.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.futurepath.actionbox.BuildConfig
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.data.DigestTime
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.ThemeMode
import java.util.Locale

/**
 * App-wide settings — plan/subscription status, the Pro-gated correction-learning toggle, and
 * the reminder system (daily digest + WAITING follow-up nudges). All backed by
 * [com.futurepath.actionbox.data.SettingsRepository] via the view model, so a toggle here takes
 * effect immediately rather than being a local UI-only flag.
 *
 * Laid out as grouped card-style sections (leading icon + title + a short current-value/state
 * subtitle per row) rather than a flat list of always-visible paragraph explanations — every
 * callback/parameter here does exactly what it did before this layout pass; only the container
 * around each one changed. There's no in-body "Settings" title: [com.futurepath.actionbox.ui.MainScreen]'s
 * top bar already shows that, and having both stacked was the reported duplicate-header bug.
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
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenVipSenders: () -> Unit,
    onOpenWeeklyInsights: () -> Unit,
    onViewCrashLogClick: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        SettingsSection(title = "Plan") {
            SettingsRow(
                icon = Icons.Filled.WorkspacePremium,
                title = "Plan",
                subtitle = if (isPro) {
                    "Pro — unlimited history, no ads"
                } else {
                    "Free — ${NotificationRepository.FREE_RETENTION_DAYS}-day history, ads shown"
                },
                trailing = { Text(if (isPro) "Manage" else "Upgrade", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                onClick = { if (isPro) openManageSubscription(context) else onUpgradeClick() }
            )
        }

        SettingsSection(title = "Smart corrections") {
            SettingsRow(
                icon = Icons.Filled.AutoAwesome,
                title = "Learn from my corrections",
                subtitle = "Classifies similar messages the way you've corrected them",
                trailing = {
                    if (isPro) {
                        Switch(checked = correctionLearningEnabled, onCheckedChange = onCorrectionLearningChange)
                    } else {
                        RequiresProChip()
                    }
                },
                onClick = if (!isPro) onUpgradeClick else null
            )
        }

        SettingsSection(title = "VIP senders") {
            SettingsRow(
                icon = Icons.Filled.Star,
                title = "VIP senders",
                subtitle = "Always route flagged senders/apps to Action",
                trailing = { if (isPro) TrailingChevron() else RequiresProChip() },
                onClick = { if (!isPro) onUpgradeClick() else onOpenVipSenders() }
            )
        }

        SettingsSection(title = "Weekly insights") {
            SettingsRow(
                icon = Icons.Filled.Insights,
                title = "Weekly insights",
                subtitle = "Volume, category breakdown, and stale WAITING items",
                trailing = { if (isPro) TrailingChevron() else RequiresProChip() },
                onClick = { if (!isPro) onUpgradeClick() else onOpenWeeklyInsights() }
            )
        }

        SettingsSection(title = "Reminders") {
            DigestToggleRow(digestsEnabled = digestsEnabled, onDigestsEnabledChange = onDigestsEnabledChange)
            if (digestsEnabled) {
                RowDivider()
                DigestTimeRow(time = digestTime, onTimeChange = onDigestTimeChange)
            }
        }

        SettingsSection(title = "Appearance") {
            ThemeModeRow(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
        }

        // Debug-only escape hatch for our own testing, since there's no Play Console listing
        // reachable from a dev sandbox to actually purchase against (see BillingRepository's
        // doc) — BuildConfig.DEBUG is a compile-time constant per build type, so R8 dead-code-
        // eliminates this entire block (including the toggle and its callback) out of release
        // builds rather than just hiding it at runtime. Never shown to a real user.
        if (BuildConfig.DEBUG) {
            SettingsSection(title = "Debug tools") {
                SettingsRow(
                    icon = Icons.Filled.BugReport,
                    title = "Simulate Pro",
                    subtitle = "No real purchase — testing only",
                    trailing = { Switch(checked = isPro, onCheckedChange = onDebugProOverrideChange) }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.Description,
                    title = "View last crash log",
                    trailing = { TrailingChevron() },
                    onClick = onViewCrashLogClick
                )
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

    SettingsRow(
        icon = Icons.Filled.Notifications,
        title = "Daily digest & waiting nudges",
        subtitle = if (digestsEnabled) "On" else "Off",
        trailing = {
            Switch(
                checked = digestsEnabled,
                onCheckedChange = { wantsEnabled ->
                    if (!wantsEnabled) {
                        onDigestsEnabledChange(false)
                        return@Switch
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
    )
}

@Composable
private fun DigestTimeRow(time: DigestTime, onTimeChange: (DigestTime) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            icon = Icons.Filled.Schedule,
            title = "Digest time",
            subtitle = formatDigestTime(time),
            trailing = { TrailingChevron() },
            onClick = { expanded = true }
        )
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
private fun ThemeModeRow(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            icon = Icons.Filled.DarkMode,
            title = "Theme",
            subtitle = formatThemeMode(themeMode),
            trailing = { TrailingChevron() },
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatThemeMode(option)) },
                    onClick = {
                        expanded = false
                        onThemeModeChange(option)
                    }
                )
            }
        }
    }
}

private fun formatThemeMode(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/** A titled group of [SettingsRow]s in one rounded card, with breathing room before the next
 * section — the "grouped under clear section headers" + "consistent rounded card-style rows"
 * part of this screen's layout. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Column {
                content()
            }
        }
    }
}

/**
 * One settings row: leading icon, title, an optional short (≤1 sentence) subtitle showing the
 * setting's current value or state, and a trailing element (switch/chip/chevron). Icon, title,
 * and trailing are laid out as siblings in one [Row] — never stacked as overlapping [Box]
 * children — which is what fixes both this screen's own icon/title overlap and matches the fix
 * already applied to the notification card's "(corrected)" label for the same reason.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        trailing()
    }
}

/** A thin inset divider between two rows inside the same [SettingsSection] card — not around the
 * whole card, so sections read as one grouped block rather than a stack of separate boxes. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

/** Small trailing chip standing in for the old "Requires Pro — tap to upgrade" paragraph — the
 * row itself still carries the tap-to-upgrade behavior via its own onClick. */
@Composable
private fun RequiresProChip() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "PRO",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun TrailingChevron() {
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
