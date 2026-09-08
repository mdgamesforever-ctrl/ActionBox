package com.futurepath.actionbox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.data.NotificationRepository

/**
 * App-wide settings — retention plan and the correction-learning toggle. Both are backed by
 * [com.futurepath.actionbox.data.SettingsRepository] via the view model, so a toggle here takes
 * effect immediately for every future notification, not just a local UI flag.
 */
@Composable
fun SettingsScreen(
    isPro: Boolean,
    onProChange: (Boolean) -> Unit,
    correctionLearningEnabled: Boolean,
    onCorrectionLearningChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Notification history")
        Text(
            text = "Free plan: notifications are kept for ${NotificationRepository.FREE_RETENTION_DAYS} days, " +
                "then automatically removed. Pro: unlimited history, nothing is ever auto-deleted.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggleRow(
            title = if (isPro) "Pro (unlimited history)" else "Free (${NotificationRepository.FREE_RETENTION_DAYS}-day history)",
            subtitle = "Standing in for real billing — flip this to try Pro's unlimited retention.",
            checked = isPro,
            onCheckedChange = onProChange
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Smart corrections")
        Text(
            text = "When you correct a notification's category, ActionBox remembers the pattern " +
                "(sender, app, or exact phrasing) and uses it to classify similar notifications " +
                "going forward. Turning this off stops new corrections from influencing future " +
                "classification — your correction history is kept and picks back up if you turn " +
                "it back on.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggleRow(
            title = "Learn from my corrections",
            subtitle = null,
            checked = correctionLearningEnabled,
            onCheckedChange = onCorrectionLearningChange
        )
    }
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
