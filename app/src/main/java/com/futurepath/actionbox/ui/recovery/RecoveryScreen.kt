package com.futurepath.actionbox.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.ui.components.NotificationCard
import java.text.DateFormat
import java.util.Date

/**
 * Recovery path for the one gap in the swipe-to-handle/snooze flow: once the undo Snackbar in
 * CategoryInboxScreen times out, a swipe that hid a notification (handledAt/snoozedUntil set,
 * see NotificationEntity) previously had no way back — the item just disappeared from every tab.
 * This screen lists both sets directly from NotificationViewModel.handledNotifications/
 * snoozedNotifications (so it can't drift from what the tabs actually hide) and lets either be
 * restored at any time, not just within the Snackbar's few-second window. Deliberately not
 * Pro-gated — accidentally losing a notification is a usability bug for every user, not a
 * premium feature (see SettingsScreen, where this is reached from a section outside the Pro
 * cluster for exactly that reason).
 */
@Composable
fun RecoveryScreen(
    handled: List<NotificationEntity>,
    snoozed: List<NotificationEntity>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit,
    onRestoreHandled: (id: Long) -> Unit,
    onRestoreSnoozed: (id: Long) -> Unit,
    isPro: Boolean = false
) {
    if (handled.isEmpty() && snoozed.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.recovery_empty_all),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        item { SectionHeader(stringResource(R.string.section_snoozed)) }
        if (snoozed.isEmpty()) {
            item { EmptySectionText(stringResource(R.string.recovery_empty_snoozed)) }
        } else {
            items(snoozed, key = { "snoozed-${it.id}" }) { notification ->
                RecoveryRow(
                    notification = notification,
                    isPro = isPro,
                    onCorrect = onCorrect,
                    snoozedUntilText = notification.snoozedUntil?.let {
                        stringResource(R.string.snackbar_snoozed_until, formatTimestamp(it))
                    },
                    onRestore = { onRestoreSnoozed(notification.id) }
                )
            }
        }

        item { SectionHeader(stringResource(R.string.section_handled)) }
        if (handled.isEmpty()) {
            item { EmptySectionText(stringResource(R.string.recovery_empty_handled)) }
        } else {
            items(handled, key = { "handled-${it.id}" }) { notification ->
                RecoveryRow(
                    notification = notification,
                    isPro = isPro,
                    onCorrect = onCorrect,
                    snoozedUntilText = null,
                    onRestore = { onRestoreHandled(notification.id) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun EmptySectionText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** [NotificationCard] plus a trailing action row — kept separate from that composable rather
 * than adding an optional "restore" slot to it, since every other caller (the grouped inbox
 * tabs, the debug feed) has no use for one. [snoozedUntilText] is null for a handled row, which
 * still keeps the Restore button end-aligned (see the [Arrangement.SpaceBetween] below — an
 * empty leading [Text] takes no width but SpaceBetween still pins the second child to the end).
 */
@Composable
private fun RecoveryRow(
    notification: NotificationEntity,
    isPro: Boolean,
    onCorrect: (Long, ClassifiedState) -> Unit,
    snoozedUntilText: String?,
    onRestore: () -> Unit
) {
    Column {
        NotificationCard(notification = notification, onCorrect = onCorrect, isPro = isPro)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = snoozedUntilText.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onRestore) {
                Text(stringResource(R.string.action_restore))
            }
        }
    }
}

private fun formatTimestamp(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))
