package com.futurepath.actionbox.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.ui.components.NotificationCard

/**
 * The raw, unfiltered capture feed — every notification in the order it arrived, with no
 * grouping by category. This used to be the app's only screen; it's now a dev/debug tool kept
 * around for our own ongoing testing (see MainActivity's long-press-to-open-debug gesture),
 * with the grouped per-category inbox screens (ui/inbox) as the actual user-facing UI.
 */
@Composable
fun NotificationFeedScreen(
    notifications: List<NotificationEntity>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit = { _, _ -> }
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.feed_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.feed_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                items(notifications, key = { it.id }) { notification ->
                    NotificationCard(notification, onCorrect)
                }
            }
        }
    }
}
