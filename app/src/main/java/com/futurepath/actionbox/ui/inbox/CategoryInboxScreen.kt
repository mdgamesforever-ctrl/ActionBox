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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.reminders.SnoozeCalculator
import com.futurepath.actionbox.reminders.SnoozeDuration
import com.futurepath.actionbox.ui.components.SwipeableNotificationCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * One tab's worth of the grouped inbox — every captured notification whose effective state
 * (see NotificationViewModel.itemsByCategory) falls in [tab]'s [InboxTab.states]. Sorted newest
 * first, same as the debug feed, since [items] is already that order coming out of Room.
 *
 * Each row is wrapped in [SwipeableNotificationCard]: swipe right marks it handled, swipe left
 * opens [SnoozeDurationDialog] — both actions get an "Undo" snackbar (see [showUndoSnackbar]),
 * since a swipe is easy to trigger by accident and neither one touches the item's category/
 * position, so undoing is just clearing the [NotificationEntity.handledAt]/[NotificationEntity.snoozedUntil]
 * timestamp that hid it — the item reappears exactly where its timestamp naturally sorts it,
 * with no separate "restore state" bookkeeping needed.
 */
@Composable
fun CategoryInboxScreen(
    tab: InboxTab,
    itemsByCategory: Map<ClassifiedState, List<NotificationEntity>>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit,
    onMarkHandled: (id: Long) -> Unit,
    onUndoHandled: (id: Long) -> Unit,
    onSnooze: (id: Long, duration: SnoozeDuration) -> Unit,
    onUndoSnooze: (id: Long) -> Unit,
    isPro: Boolean = false
) {
    val items = tab.states.flatMap { itemsByCategory[it].orEmpty() }
        .sortedByDescending { it.timestamp }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var snoozeTargetId by remember { mutableStateOf<Long?>(null) }

    val undoActionLabel = stringResource(R.string.action_undo)
    val markedHandledMessage = stringResource(R.string.snackbar_marked_handled)
    val snoozedUntilFormat = stringResource(R.string.snackbar_snoozed_until)

    Box(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(emptyMessageResFor(tab)),
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
                        isPro = isPro,
                        onMarkHandled = { id ->
                            onMarkHandled(id)
                            showUndoSnackbar(scope, snackbarHostState, markedHandledMessage, undoActionLabel) {
                                onUndoHandled(id)
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
                val message = String.format(snoozedUntilFormat, formatSnoozeUntil(duration))
                showUndoSnackbar(scope, snackbarHostState, message, undoActionLabel) {
                    onUndoSnooze(id)
                }
                snoozeTargetId = null
            },
            onDismiss = { snoozeTargetId = null }
        )
    }
}

/**
 * Shows a single "[message]" + Undo snackbar, calling [onUndo] if the user taps it before it
 * auto-dismisses (~4s, [SnackbarDuration.Short]). Dismisses whatever snackbar is currently
 * showing first — rather than letting [SnackbarHostState.showSnackbar]'s default queuing behavior
 * queue this one up behind it — so swiping a second item before the first snackbar times out
 * replaces it immediately with the latest action's undo option instead of stacking/delaying it.
 */
private fun showUndoSnackbar(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    message: String,
    actionLabel: String,
    onUndo: () -> Unit
) {
    scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            onUndo()
        }
    }
}

/** "Snoozed until [time]" display text for the undo snackbar — computed independently from
 * whatever timestamp [com.futurepath.actionbox.viewmodel.NotificationViewModel.snooze] actually
 * stores (see [SnoozeCalculator], a pure function of the duration and current time), since this
 * is purely for display and the two calls happening a few milliseconds apart makes no visible
 * difference once formatted. */
private fun formatSnoozeUntil(duration: SnoozeDuration): String {
    val untilMs = SnoozeCalculator.resolveUntil(duration, System.currentTimeMillis())
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(untilMs))
}

/**
 * The localized duration label shown in [SnoozeDurationDialog] — deliberately separate from
 * [SnoozeCalculator.label] (which stays a plain, English-only pure function backing
 * SnoozeCalculatorTest) since that's pure decision logic with its own JVM unit test, not display
 * text; only the UI-facing label needs to follow the app's selected language.
 */
@Composable
private fun snoozeDurationLabel(duration: SnoozeDuration): String = when (duration) {
    SnoozeDuration.ONE_HOUR -> stringResource(R.string.snooze_one_hour)
    SnoozeDuration.TOMORROW -> stringResource(R.string.snooze_tomorrow)
    SnoozeDuration.NEXT_WEEK -> stringResource(R.string.snooze_next_week)
}

@Composable
private fun SnoozeDurationDialog(onSelect: (SnoozeDuration) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_snooze_until_title)) },
        text = {
            Column {
                SnoozeDuration.entries.forEach { duration ->
                    TextButton(onClick = { onSelect(duration) }, modifier = Modifier.fillMaxWidth()) {
                        Text(snoozeDurationLabel(duration))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

private fun emptyMessageResFor(tab: InboxTab): Int = when (tab) {
    InboxTab.ACTION -> R.string.empty_inbox_action
    InboxTab.WAITING -> R.string.empty_inbox_waiting
    InboxTab.DEADLINE -> R.string.empty_inbox_deadline
    InboxTab.REPLY -> R.string.empty_inbox_reply
    InboxTab.OTHER -> R.string.empty_inbox_other
}
