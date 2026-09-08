package com.futurepath.actionbox.ui.settings

import android.content.Context
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.data.VipSenderEntity

/**
 * Pro feature — see NotificationRepository.capture()'s VIP-escalation check. Lets the user flag
 * specific senders (or entire apps) so their notifications always land in the Action tab,
 * overriding whatever the classifier would normally decide. Reached only through a Pro-gated
 * entry point in SettingsScreen, so this screen itself doesn't need its own Pro check.
 *
 * The "Add VIP" dialog picks from apps/senders [notifications] has ALREADY captured, rather than
 * a free-text field — [NotificationEntity.sourceApp] is the raw Android package name (e.g.
 * "com.whatsapp", not "WhatsApp"; see CapturedNotificationListenerService), and
 * [NotificationEntity.sender] is whatever exact string a given app puts in its notification
 * title. A free-text field inviting a human-friendly guess ("WhatsApp") could never exact-match
 * what's actually stored — which is exactly why VIP escalation silently never matched anything
 * before this. Picking from real captured values makes an exact match structurally guaranteed;
 * [resolveAppLabel] is used only to make the picker itself readable, never for matching.
 */
@Composable
fun VipSendersScreen(
    vipSenders: List<VipSenderEntity>,
    notifications: List<NotificationEntity>,
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
            notifications = notifications,
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
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = resolveAppLabel(context, entry.sourceApp), style = MaterialTheme.typography.bodyLarge)
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
private fun AddVipDialog(
    notifications: List<NotificationEntity>,
    onAdd: (sourceApp: String, sender: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val distinctApps = remember(notifications) { notifications.map { it.sourceApp }.distinct().sorted() }
    var selectedApp by remember { mutableStateOf(distinctApps.firstOrNull().orEmpty()) }
    val sendersForApp = remember(notifications, selectedApp) {
        notifications
            .filter { it.sourceApp == selectedApp && it.sender.isNotBlank() }
            .map { it.sender }
            .distinct()
            .sorted()
    }
    var selectedSender by remember(selectedApp) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add VIP") },
        text = {
            if (distinctApps.isEmpty()) {
                Text(
                    "No notifications captured yet — VIP entries are picked from apps and " +
                        "senders you've already received notifications from."
                )
            } else {
                Column {
                    Text("App", style = MaterialTheme.typography.labelMedium)
                    PickerRow(
                        label = resolveAppLabel(context, selectedApp),
                        options = distinctApps,
                        optionLabel = { resolveAppLabel(context, it) },
                        onSelect = { selectedApp = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Sender (optional — blank = whole app)", style = MaterialTheme.typography.labelMedium)
                    PickerRow(
                        label = selectedSender ?: "Entire app",
                        options = listOf(null) + sendersForApp,
                        optionLabel = { it ?: "Entire app" },
                        onSelect = { selectedSender = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(selectedApp, selectedSender.orEmpty()) },
                enabled = selectedApp.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A single-choice picker (label button + dropdown), the same interaction pattern already used
 * for the digest-time and theme pickers in SettingsScreen.kt. */
@Composable
private fun <T> PickerRow(label: String, options: List<T>, optionLabel: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

/** Display-only: resolves a package name to the app's installed label ("WhatsApp") for
 * readability. Never used for VIP matching itself — see this file's doc for why that has to
 * compare exact stored values instead. Falls back to the raw package name if the app can't be
 * resolved (e.g. it's since been uninstalled). */
private fun resolveAppLabel(context: Context, packageName: String): String {
    if (packageName.isBlank()) return packageName
    return try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        packageName
    }
}
