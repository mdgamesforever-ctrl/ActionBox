package com.futurepath.actionbox.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.search.NotificationSearch
import com.futurepath.actionbox.ui.components.NotificationCard

/**
 * Cross-app keyword search reached from the top bar's search icon (see ui/MainScreen.kt).
 * Searches [notifications] — the same already-loaded, retention-filtered list every other
 * screen reads from — via [NotificationSearch.search], and reuses [NotificationCard] for
 * results so each one still shows its category badge for context.
 */
@Composable
fun SearchScreen(
    notifications: List<NotificationEntity>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(notifications, query) { NotificationSearch.search(notifications, query) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search by sender or message") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        when {
            query.isBlank() -> EmptyState("Search across every notification you have stored, regardless of category or app.")
            results.isEmpty() -> EmptyState("No matches for \"$query\".")
            else -> LazyColumn {
                items(results, key = { it.id }) { notification ->
                    NotificationCard(notification, onCorrect)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}
