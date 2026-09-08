package com.futurepath.actionbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.data.VipSenderEntity

/**
 * Pro feature — see NotificationRepository.capture()'s VIP-escalation check. Lets the user flag
 * specific senders (or entire apps) so their notifications always land in the Action tab,
 * overriding whatever the classifier would normally decide. Reached only through a Pro-gated
 * entry point in SettingsScreen, so this screen itself doesn't need its own Pro check.
 */
@Composable
fun VipSendersScreen(
    vipSenders: List<VipSenderEntity>,
    onAdd: (sourceApp: String, sender: String) -> Unit,
    onRemove: (VipSenderEntity) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add VIP")
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "Notifications from a VIP sender or app always land in Action, regardless " +
                    "of what the classifier would normally pick.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (vipSenders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No VIPs yet — tap + to add a sender or app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(vipSenders, key = { it.id }) { entry ->
                        VipRow(entry = entry, onRemove = { onRemove(entry) })
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddVipDialog(
            onAdd = { sourceApp, sender ->
                onAdd(sourceApp, sender)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun VipRow(entry: VipSenderEntity, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.sourceApp, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (entry.sender.isBlank()) "Entire app" else entry.sender,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove")
        }
    }
}

@Composable
private fun AddVipDialog(onAdd: (sourceApp: String, sender: String) -> Unit, onDismiss: () -> Unit) {
    var sourceApp by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add VIP") },
        text = {
            Column {
                OutlinedTextField(
                    value = sourceApp,
                    onValueChange = { sourceApp = it },
                    label = { Text("App (e.g. WhatsApp)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it },
                    label = { Text("Sender (optional — blank = whole app)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(sourceApp.trim(), sender.trim()) },
                enabled = sourceApp.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
