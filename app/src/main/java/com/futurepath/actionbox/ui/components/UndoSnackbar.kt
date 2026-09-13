package com.futurepath.actionbox.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows a single "[message]" + Undo snackbar, calling [onUndo] if the user taps it before it
 * auto-dismisses (~4s, [SnackbarDuration.Short]). Dismisses whatever snackbar is currently
 * showing first — rather than letting [SnackbarHostState.showSnackbar]'s default queuing behavior
 * queue this one up behind it — so triggering a second action before the first snackbar times out
 * replaces it immediately with the latest action's undo option instead of stacking/delaying it.
 * Shared by every screen with a swipe-driven undo action (ui/inbox/CategoryInboxScreen,
 * ui/recovery/RecoveryScreen) so the timing/behavior can't drift between them.
 */
fun showUndoSnackbar(
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
