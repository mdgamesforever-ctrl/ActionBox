package com.futurepath.actionbox.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.ui.components.NotificationCard

/**
 * One tab's worth of the grouped inbox — every captured notification whose effective state
 * (see NotificationViewModel.itemsByCategory) falls in [tab]'s [InboxTab.states]. Sorted newest
 * first, same as the debug feed, since [items] is already that order coming out of Room.
 */
@Composable
fun CategoryInboxScreen(
    tab: InboxTab,
    itemsByCategory: Map<ClassifiedState, List<NotificationEntity>>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit
) {
    val items = tab.states.flatMap { itemsByCategory[it].orEmpty() }
        .sortedByDescending { it.timestamp }

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
                NotificationCard(notification, onCorrect)
            }
        }
    }
}

private fun emptyMessageFor(tab: InboxTab): String = when (tab) {
    InboxTab.ACTION -> "Nothing needs action right now."
    InboxTab.WAITING -> "Nothing you're waiting on right now."
    InboxTab.DEADLINE -> "No upcoming deadlines."
    InboxTab.REPLY -> "No messages waiting on a reply."
    InboxTab.OTHER -> "Nothing here — FYI notices and noise will show up in this tab."
}
