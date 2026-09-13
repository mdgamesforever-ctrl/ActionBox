package com.futurepath.actionbox.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.ui.components.NotificationCard
import com.futurepath.actionbox.ui.components.showUndoSnackbar
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
 *
 * Also the only place a notification can be permanently deleted, since neither of those states
 * is meant to accumulate in storage forever: swipe-to-delete per row (own undo Snackbar, same
 * pattern as CategoryInboxScreen's swipes) for one-offs, plus a "Select" mode with per-row
 * checkboxes, a Select all/Deselect all toggle, and a confirmed multi-delete for clearing many
 * (or everything) at once. The bulk path gets a confirmation dialog instead of an undo Snackbar
 * — deleting a large, user-chosen batch in one tap (up to "everything on this screen") is a
 * meaningfully bigger mistake to make silently reversible-by-toast than a single accidental
 * swipe is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    handled: List<NotificationEntity>,
    snoozed: List<NotificationEntity>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit,
    onRestoreHandled: (id: Long) -> Unit,
    onRestoreSnoozed: (id: Long) -> Unit,
    onDeleteNotification: (NotificationEntity) -> Unit,
    onDeleteNotifications: (List<NotificationEntity>) -> Unit,
    onRestoreNotification: (NotificationEntity) -> Unit,
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

    val allItems = remember(handled, snoozed) { handled + snoozed }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var pendingDelete by remember { mutableStateOf<List<NotificationEntity>?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoActionLabel = stringResource(R.string.action_undo)
    val deletedMessage = stringResource(R.string.snackbar_deleted)

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SelectionBar(
            selectionMode = selectionMode,
            selectedCount = selectedIds.size,
            totalCount = allItems.size,
            onEnterSelectionMode = { selectionMode = true },
            onExitSelectionMode = { exitSelectionMode() },
            onSelectAllToggle = {
                selectedIds = if (selectedIds.size >= allItems.size) {
                    emptySet()
                } else {
                    allItems.map { it.id }.toSet()
                }
            },
            onDeleteSelected = {
                pendingDelete = allItems.filter { it.id in selectedIds }
            }
        )

        Box(modifier = Modifier.weight(1f)) {
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
                            onRestore = { onRestoreSnoozed(notification.id) },
                            onDelete = {
                                onDeleteNotification(notification)
                                showUndoSnackbar(scope, snackbarHostState, deletedMessage, undoActionLabel) {
                                    onRestoreNotification(notification)
                                }
                            },
                            selectionMode = selectionMode,
                            selected = notification.id in selectedIds,
                            onToggleSelected = {
                                selectedIds = if (notification.id in selectedIds) {
                                    selectedIds - notification.id
                                } else {
                                    selectedIds + notification.id
                                }
                            }
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
                            onRestore = { onRestoreHandled(notification.id) },
                            onDelete = {
                                onDeleteNotification(notification)
                                showUndoSnackbar(scope, snackbarHostState, deletedMessage, undoActionLabel) {
                                    onRestoreNotification(notification)
                                }
                            },
                            selectionMode = selectionMode,
                            selected = notification.id in selectedIds,
                            onToggleSelected = {
                                selectedIds = if (notification.id in selectedIds) {
                                    selectedIds - notification.id
                                } else {
                                    selectedIds + notification.id
                                }
                            }
                        )
                    }
                }
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    pendingDelete?.let { toDelete ->
        DeleteConfirmDialog(
            count = toDelete.size,
            onConfirm = {
                onDeleteNotifications(toDelete)
                pendingDelete = null
                exitSelectionMode()
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** The in-screen action bar: a single "Select" entry point normally, replaced by a selection
 * count + Select all/Deselect all + Delete + close-selection-mode toolbar once active. Kept as
 * part of this screen's own content (not the shared MainScreen top bar) since selection mode is
 * entirely local, transient UI state that only this screen cares about. */
@Composable
private fun SelectionBar(
    selectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onEnterSelectionMode: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onSelectAllToggle: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            IconButton(onClick = onExitSelectionMode) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Text(
                text = stringResource(R.string.recovery_selected_count, selectedCount),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            TextButton(onClick = onSelectAllToggle) {
                Text(
                    stringResource(
                        if (selectedCount >= totalCount) R.string.action_deselect_all else R.string.action_select_all
                    )
                )
            }
            IconButton(onClick = onDeleteSelected, enabled = selectedCount > 0) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onEnterSelectionMode) {
                Text(stringResource(R.string.action_select))
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.recovery_delete_confirm_title, count, count)) },
        text = { Text(stringResource(R.string.recovery_delete_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
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
 *
 * Swipe-to-delete ([SwipeToDismissBox]) and selection-mode checkboxes are mutually exclusive on
 * purpose: a drag gesture and a tap-to-toggle-checkbox gesture on the same row would fight each
 * other, so selection mode replaces the swipe wrapper entirely rather than layering both on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryRow(
    notification: NotificationEntity,
    isPro: Boolean,
    onCorrect: (Long, ClassifiedState) -> Unit,
    snoozedUntilText: String?,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit
) {
    val content: @Composable () -> Unit = {
        // fillMaxWidth + an opaque background matching the screen: SwipeToDismissBox's pink
        // delete background (see DeleteSwipeBackground below) always renders — it's meant to
        // stay fully hidden behind this content at rest purely because this content fully
        // overlaps it, not because the background conditionally hides itself. Without an
        // explicit opaque backing here, the transparent gaps in this Row (this container itself,
        // and the snoozedUntil/Restore row below NotificationCard, neither of which paint their
        // own background) let that pink layer bleed through even when nothing has been swiped —
        // this was the actual bug, not the swipe offset (rememberSwipeToDismissBoxState already
        // defaults to Settled/offset-0, which was never the issue).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
            verticalAlignment = Alignment.Top
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected() },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                NotificationCard(notification = notification, onCorrect = onCorrect, isPro = isPro)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        // Stable target for RecoveryScreenSwipeBackgroundTest — this specific row
                        // (transparent by design; only the outer Row's background covers it) is
                        // exactly what let the delete-swipe background bleed through.
                        .testTag("recoveryRowFooter"),
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
    }

    if (selectionMode) {
        content()
    } else {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value != SwipeToDismissBoxValue.Settled) {
                    onDelete()
                }
                true
            }
        )
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = { DeleteSwipeBackground(dismissState.targetValue) }
        ) {
            content()
        }
    }
}

@Composable
private fun DeleteSwipeBackground(targetValue: SwipeToDismissBoxValue) {
    val alignment = if (targetValue == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment
    ) {
        if (targetValue != SwipeToDismissBoxValue.Settled) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.onError
            )
        }
    }
}

private fun formatTimestamp(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))
