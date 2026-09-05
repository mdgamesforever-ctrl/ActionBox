package com.futurepath.actionbox.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.data.NotificationDebugEvent
import java.text.DateFormat
import java.util.Date

@Composable
fun NotificationFeedScreen(events: List<NotificationDebugEvent>, capturedCount: Int) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.feed_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
        )
        // Ground truth for whether "DUPLICATE (ignored)" rows are really excluded from the
        // real table: compare this against the number of events labeled CAPTURED below.
        Text(
            text = "$capturedCount row(s) in captured_notifications · ${events.size} event(s) shown below",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp, end = 16.dp)
        )

        if (events.isEmpty()) {
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
                items(events, key = { it.id }) { event ->
                    DebugEventRow(event)
                }
            }
        }
    }
}

@Composable
private fun DebugEventRow(event: NotificationDebugEvent) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = event.sourceApp, style = MaterialTheme.typography.labelMedium)
            Text(
                text = outcomeLabel(event.outcome),
                style = MaterialTheme.typography.labelSmall,
                color = outcomeColor(event.outcome)
            )
            if (event.resolvedSender.isNotBlank()) {
                Text(text = event.resolvedSender, style = MaterialTheme.typography.titleMedium)
            }
            if (event.resolvedText.isNotBlank()) {
                Text(text = event.resolvedText, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = DateFormat.getDateTimeInstance().format(Date(event.receivedAt)),
                style = MaterialTheme.typography.labelSmall
            )

            if (isExpanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                RawDetailField("notificationKey", event.notificationKey)
                RawDetailField("extractionSource", event.extractionSource)
                RawDetailField("resolvedTimestamp", "${event.resolvedTimestamp} (${DateFormat.getDateTimeInstance().format(Date(event.resolvedTimestamp))})")
                RawDetailField("EXTRA_TEXT", event.rawExtraText ?: "(absent)")
                RawDetailField("EXTRA_BIG_TEXT", event.rawExtraBigText ?: "(absent)")
                RawDetailField("MessagingStyle.messages", event.messagingStyleDump)
                event.conflictDetail?.let { RawDetailField("Why duplicate", it) }
            }
        }
    }
}

@Composable
private fun RawDetailField(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun outcomeLabel(outcome: String): String = when (outcome) {
    "captured" -> "CAPTURED"
    "duplicate_ignored" -> "DUPLICATE (ignored)"
    "filtered_noise" -> "FILTERED: call/system noise"
    "filtered_ongoing" -> "FILTERED: ongoing"
    "blank_skipped" -> "SKIPPED: blank"
    else -> outcome.uppercase()
}

@Composable
private fun outcomeColor(outcome: String) = when (outcome) {
    "captured" -> MaterialTheme.colorScheme.primary
    "duplicate_ignored" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}
