package com.futurepath.actionbox.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.data.NotificationEntity
import java.text.DateFormat
import java.util.Date

@Composable
fun NotificationFeedScreen(notifications: List<NotificationEntity>) {
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
                    NotificationRow(notification)
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: NotificationEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = notification.sourceApp, style = MaterialTheme.typography.labelMedium)
            if (notification.sender.isNotBlank()) {
                Text(text = notification.sender, style = MaterialTheme.typography.titleMedium)
            }
            if (notification.text.isNotBlank()) {
                Text(text = notification.text, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = DateFormat.getDateTimeInstance().format(Date(notification.timestamp)),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
