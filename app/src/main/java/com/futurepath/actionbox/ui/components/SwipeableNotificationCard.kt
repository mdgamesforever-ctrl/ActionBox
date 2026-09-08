package com.futurepath.actionbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity

/**
 * Wraps [NotificationCard] with the two swipe gestures shared by every grouped inbox tab (see
 * ui/inbox/CategoryInboxScreen.kt):
 *  - Swipe right (StartToEnd) marks the item handled/done via [onMarkHandled] and lets the swipe
 *    complete — the item then drops out of the active inbox (see
 *    [com.futurepath.actionbox.data.groupActiveByCategory]) so this composable simply leaves
 *    composition rather than needing to animate/reset itself.
 *  - Swipe left (EndToStart) opens the snooze duration picker via [onOpenSnooze] instead of
 *    dismissing the card itself — [confirmValueChange] returning false here is what makes the
 *    swipe snap back immediately, leaving the actual outcome up to whichever duration (or none)
 *    the user picks in that dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableNotificationCard(
    notification: NotificationEntity,
    onCorrect: (Long, ClassifiedState) -> Unit,
    onMarkHandled: (Long) -> Unit,
    onOpenSnooze: (Long) -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onMarkHandled(notification.id)
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onOpenSnooze(notification.id)
                    false
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        }
    )

    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackground(state.targetValue) }
    ) {
        NotificationCard(notification, onCorrect)
    }
}

@Composable
private fun SwipeBackground(targetValue: SwipeToDismissBoxValue) {
    val color = when (targetValue) {
        SwipeToDismissBoxValue.StartToEnd -> Color(0xFF43A047)
        SwipeToDismissBoxValue.EndToStart -> Color(0xFF1E88E5)
        SwipeToDismissBoxValue.Settled -> Color.Transparent
    }
    val alignment = if (targetValue == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment
    ) {
        when (targetValue) {
            SwipeToDismissBoxValue.StartToEnd ->
                Icon(Icons.Filled.CheckCircle, contentDescription = "Mark handled", tint = Color.White)
            SwipeToDismissBoxValue.EndToStart ->
                Icon(Icons.Filled.Snooze, contentDescription = "Snooze", tint = Color.White)
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
}
