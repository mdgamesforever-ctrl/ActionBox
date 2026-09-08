package com.futurepath.actionbox.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.reminders.SnoozeCalculator
import com.futurepath.actionbox.reminders.SnoozeDuration
import com.futurepath.actionbox.ui.components.SwipeableNotificationCard
import kotlinx.coroutines.launch

/**
 * One tab's worth of the grouped inbox — every captured notification whose effective state
 * (see NotificationViewModel.itemsByCategory) falls in [tab]'s [InboxTab.states]. Sorted newest
 * first, same as the debug feed, since [items] is already that order coming out of Room.
 *
 * Each row is wrapped in [SwipeableNotificationCard]: swipe right marks it handled (with an
 * "Undo" snackbar, since swipes are easy to trigger by accident), swipe left opens
 * [SnoozeDurationDialog].
 */
@Composable
fun CategoryInboxScreen(
    tab: InboxTab,
    itemsByCategory: Map<ClassifiedState, List<NotificationEntity>>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit,
    onMarkHandled: (id: Long) -> Unit,
    onUndoHandled: (id: Long) -> Unit,
    onSnooze: (id: Long, duration: SnoozeDuration) -> Unit
) {
    val items = tab.states.flatMap { itemsByCategory[it].orEmpty() }
        .sortedByDescending { it.timestamp }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var snoozeTargetId by remember { mutableStateOf<Long?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emptyMessageFor(tab),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(items, key = { it.id }) { notification ->
                    SwipeableNotificationCard(
                        notification = notification,
                        onCorrect = onCorrect,
                        onMarkHandled = { id ->
                            onMarkHandled(id)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Marked handled",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    onUndoHandled(id)
                                }
                            }
                        },
                        onOpenSnooze = { id -> snoozeTargetId = id }
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    snoozeTargetId?.let { id ->
        SnoozeDurationDialog(
            onSelect = { duration ->
                onSnooze(id, duration)
                snoozeTargetId = null
            },
            onDismiss = { snoozeTargetId = null }
        )
    }
}

@Composable
private fun SnoozeDurationDialog(onSelect: (SnoozeDuration) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snooze until…") },
        text = {
            Column {
                SnoozeDuration.entries.forEach { duration ->
                    TextButton(onClick = { onSelect(duration) }, modifier = Modifier.fillMaxWidth()) {
                        Text(SnoozeCalculator.label(duration))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun emptyMessageFor(tab: InboxTab): String = when (tab) {
    InboxTab.ACTION -> "Nothing needs action right now."
    InboxTab.WAITING -> "Nothing you're waiting on right now."
    InboxTab.DEADLINE -> "No upcoming deadlines."
    InboxTab.REPLY -> "No messages waiting on a reply."
    InboxTab.OTHER -> "Nothing here — FYI notices and noise will show up in this tab."
}
